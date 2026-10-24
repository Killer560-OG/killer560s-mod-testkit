package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod aura-themes-1015 (killer560 2026-10-15, two to-do items).
 * <ul>
 *   <li>1420 Lever Aura is part of the Secret Aura tab: no Lever Aura card or tab class, the tab holds In Rooms / In Boss and,
 *       with In Boss on, the Section Levers and Boss Lever rows (the Lights rows are the Lights Device's own tab since mod
 *       lights-aura-1015, {@link LightsDeviceUiCases}); the card stands for Secret Aura AND In Boss;
 *       an old Lever Aura file ("enabled": true, its other keys) migrates on load (In Boss on, the rest kept, the old key gone)
 *       and "enabled": false never turns In Boss off. Cheat jar only.</li>
 *   <li>1421 every overlay with a Menu Theme setting ({@code gui.OverlayThemes.ids()}): the setting is in its tab and starts on
 *       Default (the overlay's own look, no palette); a press on the button steps to Amber; Amber, Ocean and Light each give a
 *       palette and the colours the overlay really reads differ from Default's and from each other; a custom theme saved at
 *       run time is in the choice list, is reached by pressing the button, shows its name, draws its own colours, follows a
 *       rename and a recolour, survives a save and reload, and reads as Default once deleted. The same list is what a menu
 *       screen's own Theme setting offers (Termism).</li>
 * </ul>
 */
final class AuraThemeCases {

    private AuraThemeCases() {
    }

    private static final String SA_TAB = "gui.tab.SecretAuraTab";
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String LA = "leveraura.LeverAuraConfig";

    private static byte[] snapshot(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restore(Path p, byte[] bytes) throws Exception {
        if (bytes == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(Object tab) {
        return (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
        }
        return out;
    }

    private static boolean hasRow(List<String> labels, String name) {
        for (String l : labels) {
            if (l != null && (l.equals(name) || l.startsWith(name + ":"))) {
                return true;
            }
        }
        return false;
    }

    // ---- 1420 -----------------------------------------------------------------------------------------------------

    static void secretAuraMerge(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            dev.testkit.gametest.Scenario.skipped("1420-ui-secret-aura-merge", "cheat jar only");
            return;
        }
        c.check(!Mod.has("gui.tab.LeverAuraTab"), "gui.tab.LeverAuraTab still exists");
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            c.onClient(mc -> {
                ModScreenDriver.flatten(d.tree(), flat);
                return null;
            });
            Object secretAura = null;
            for (ModScreenDriver.Node n : flat) {
                c.check(!n.name().equals("Lever Aura"), "the menu still has a '" + n.path() + "' card");
                if (!n.folder() && n.name().equals("Secret Aura")) {
                    secretAura = n.tab();
                }
            }
            c.check(secretAura != null, "no Secret Aura tab in the menu");
            MenuLayoutCases.close(c, d);

            Object tab = secretAura;
            Path laPath = (Path) Mod.field(LA, "CONFIG_PATH");
            Path cuPath = (Path) Mod.field(CU, "CONFIG_PATH");
            Path ldPath = (Path) Mod.field("lightsdevice.LightsDeviceConfig", "CONFIG_PATH");
            byte[] ldBefore = snapshot(ldPath);
            byte[] laBefore = snapshot(laPath);
            byte[] cuBefore = snapshot(cuPath);
            Object cu = Mod.cfg(CU);
            boolean sa0 = (Boolean) Mod.call(cu, "isSecretAuraEnabled");
            boolean boss0 = (Boolean) Mod.call(cu, "isAuraBossLevers");
            try {
                // the tab: Secret Aura off, In Boss off
                Mod.call(cu, "setSecretAuraEnabled", false);
                Mod.call(cu, "setAuraBossLevers", false);
                List<String> off = c.onClient(mc -> labels(build(tab)));
                c.note("both off: " + off);
                c.check(hasRow(off, "Secret Aura") && hasRow(off, "In Boss"),
                        "the tab should show Secret Aura and In Boss while both are off: " + off);
                c.check(!hasRow(off, "S2 Section Levers") && !hasRow(off, "Boss Lever CPS"),
                        "the boss-lever options show with In Boss off: " + off);
                // In Boss on, Secret Aura still off: In Boss is its own feature
                Mod.call(cu, "setAuraBossLevers", true);
                List<String> boss = c.onClient(mc -> labels(build(tab)));
                c.note("In Boss on: " + boss);
                for (String row : new String[]{"S2 Section Levers",
                        "Boss Lever Range", "Boss Lever CPS", "Boss Lever Swing Hand", "Boss Lever Chat Feedback"}) {
                    c.check(hasRow(boss, row), "In Boss on but no '" + row + "' row: " + boss);
                }
                // mod lights-aura-1015: the Lights rows left In Boss for the Lights Device tab
                c.check(!hasRow(boss, "Pre-Flick Lights Before S2") && !hasRow(boss, "Finish Lights When S2 Opens"),
                        "In Boss still builds the Lights rows: " + boss);
                c.check(!hasRow(boss, "In Rooms"), "In Rooms shows with Secret Aura off: " + boss);
                Mod.call(cu, "setSecretAuraEnabled", true);
                List<String> both = c.onClient(mc -> labels(build(tab)));
                c.check(hasRow(both, "In Rooms") && hasRow(both, "Chests") && hasRow(both, "In Boss"),
                        "Secret Aura on should show In Rooms, Chests and In Boss: " + both);
                // the card stands for both
                Object ms = c.onClient(mc -> Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { }));
                c.check(ms != null && ((List<?>) Mod.call(ms, "parts")).size() == 2,
                        "the Secret Aura card should stand for two switches (Secret Aura, In Boss)");

                // migration: a Lever Aura file as it was before the merge
                Mod.call(cu, "setAuraBossLevers", false);
                Mod.call(cu, "save");
                Files.createDirectories(laPath.getParent());
                Files.writeString(laPath, "{\"enabled\": true, \"cps\": 9, \"sectionLevers\": true, \"lightsFinish\": false,"
                        + " \"range\": 3.0, \"chatFeedback\": false}", StandardCharsets.UTF_8);
                Mod.staticCall(CU, "load");
                Mod.staticCall(LA, "load");
                Object la = Mod.cfg(LA);
                c.check((Boolean) Mod.get(CU, "isAuraBossLevers"), "an old enabled:true file did not turn In Boss on");
                c.check((Boolean) Mod.call(la, "isEnabledRaw"), "Lever Aura's own switch should read In Boss");
                c.check(((Integer) Mod.call(la, "getCps")) == 9, "cps 9 did not carry over: " + Mod.call(la, "getCps"));
                c.check((Boolean) Mod.call(la, "isSectionLeversRaw"), "sectionLevers did not carry over");
                // lightsFinish now belongs to the Lights Device (mod lights-aura-1015): it carries over THERE.
                c.check(!(Boolean) Mod.get("lightsdevice.LightsDeviceConfig", "isFinish"), "lightsFinish false did not carry over");
                c.check(!(Boolean) Mod.call(la, "isChatFeedback"), "chatFeedback false did not carry over");
                c.check(Math.abs((Double) Mod.call(la, "getRange") - 3.0) < 0.01, "range 3.0 did not carry over");
                String rewritten = Files.readString(laPath, StandardCharsets.UTF_8);
                c.check(!rewritten.contains("\"enabled\""), "the old enabled key is still in the file: " + rewritten);
                c.check(rewritten.contains("\"cps\": 9"), "the rewritten file lost cps: " + rewritten);
                String cuFile = Files.readString(cuPath, StandardCharsets.UTF_8);
                c.check(cuFile.contains("\"auraBossLevers\": true"), "In Boss was not saved to the Secret Aura file: " + cuFile);
                // a second load changes nothing
                Mod.staticCall(LA, "load");
                c.check((Boolean) Mod.get(CU, "isAuraBossLevers"), "a second load switched In Boss off");
                // enabled:false never switches it off
                Files.writeString(laPath, "{\"enabled\": false, \"cps\": 5}", StandardCharsets.UTF_8);
                Mod.staticCall(LA, "load");
                c.check((Boolean) Mod.get(CU, "isAuraBossLevers"), "an old enabled:false file switched In Boss off");
                c.note("migration: enabled:true -> In Boss on, cps/sectionLevers/lightsFinish/chatFeedback/range kept, key dropped");
            } finally {
                restore(laPath, laBefore);
                restore(cuPath, cuBefore);
                restore(ldPath, ldBefore);
                Mod.staticCall(CU, "load");
                Mod.staticCall(LA, "load");
                Mod.staticCall("lightsdevice.LightsDeviceConfig", "load");
                Object cu2 = Mod.cfg(CU);
                Mod.call(cu2, "setSecretAuraEnabled", sa0);
                Mod.call(cu2, "setAuraBossLevers", boss0);
            }
        }
    }

    // ---- 1421 -----------------------------------------------------------------------------------------------------

    /**
     * id -> {tab class (null: the button is built by the factory its screen calls), button label, config class that
     * gates the button (or null), what the button reads on Default}, in {@code OverlayThemes.ids()} order.
     * <p>Mod 4a7ffc31 (killer560, 2026-10-18: "one theme specifically per mod section") stores the Croesus chest panel
     * and start button under the Croesus section, the Experiments start button (experiments_start, new there) under
     * Experiments' "profit" and the Leap Menu under the Leap Order's: each is set by that tab's one "Theme" row, which
     * reads "Theme Default". The Inventory Theme's row is "Theme" too. Mod 36119eff added the Custom Scoreboard board,
     * whose "Theme" row 3a957c1a moved into the scoreboard editor's Settings page (ScreenThemes.overlayButton there).
     */
    private static final Map<String, String[]> TABS = new LinkedHashMap<>();

    static {
        TABS.put("storage_overlay", new String[]{"gui.tab.StorageOverlayTab", "Menu Theme", "storageoverlay.StorageOverlayConfig", "Default"});
        TABS.put("inventory_theme", new String[]{"gui.tab.InventoryThemeTab", "Theme", "inventorytheme.InventoryThemeConfig", "Default"});
        TABS.put("inventory_hud", new String[]{"gui.tab.InventoryHudTab", "Menu Theme", "inventoryhud.InventoryHudConfig", "Default"});
        TABS.put("chest_profit", new String[]{"gui.tab.CroesusTab", "Theme", null, "Theme Default"});
        TABS.put("start_buttons", new String[]{"gui.tab.CroesusTab", "Theme", null, "Theme Default"});
        TABS.put("experiments_start", new String[]{"gui.tab.ExperimentsTab", "Theme", null, "Theme Default"});
        TABS.put("leap_menu", new String[]{"gui.tab.LeapMenuTab", "Theme", "spiritleap.SpiritLeapOverlayConfig", "Theme Default"});
        TABS.put("item_browser", new String[]{"gui.tab.ItemBrowserTab", "Menu Theme", "itembrowser.ItemBrowserConfig", "Default"});
        TABS.put("terminal_solver", new String[]{"gui.tab.TerminalSolverTab", "Menu Theme", "terminals.TerminalSolverConfig", "Default"});
        TABS.put("custom_scoreboard", new String[]{null, "Theme", null, "Default"});
    }

    private static final String OT = "gui.OverlayThemes";
    private static final String ST = "gui.ScreenThemes";

    private static Object tabOf(String cls) throws Exception {
        return Mod.cls(cls).getConstructor().newInstance();
    }

    private static AbstractWidget button(UiCase c, String id) throws Exception {
        String[] t = TABS.get(id);
        if (t[2] != null) {
            Mod.set(t[2], "setEnabled", true);
        }
        if (t[0] == null) {
            // The scoreboard editor's Theme row: the same call ScoreboardEditorScreen.buildSettings makes.
            return c.onClient(mc -> (AbstractWidget) Mod.staticCall(ST, "overlayButton", id, t[1], 0, 0, 200, 20));
        }
        Object tab = tabOf(t[0]);
        List<AbstractWidget> ws = c.onClient(mc -> build(tab));
        for (AbstractWidget w : ws) {
            String s = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
            if (s != null && s.startsWith(t[1] + ": ")) {
                return w;
            }
        }
        throw new AssertionError(id + ": no '" + t[1] + "' row in " + t[0] + ": " + labels(ws));
    }

    private static String text(AbstractWidget w) {
        return net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
    }

    private static void press(UiCase c, AbstractWidget w) {
        c.onClient(mc -> {
            Mod.call(w, "press");
            return null;
        });
    }

    /** The colour the overlay reads from its Menu Theme right now (the same method its drawing calls). */
    private static int drawn(String id) {
        Object pal = Mod.staticCall(OT, "of", id);
        switch (id) {
            case "storage_overlay": {
                Object colours = Mod.call(Mod.cfg("storageoverlay.StorageOverlayConfig"), "colours");
                return (Integer) Mod.call(colours, "panelBg");
            }
            case "inventory_theme": {
                Object palette = Mod.call(Mod.cfg("inventorytheme.InventoryThemeConfig"), "getPalette");
                return (Integer) Mod.call(palette, "panel");
            }
            default:
                return pal == null ? 0 : (Integer) Mod.call(pal, "panel");
        }
    }

    private static String choice(String id) {
        return (String) Mod.staticCall(ST, "get", id);
    }

    static void overlayThemes(UiCase c) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) Mod.staticCall(OT, "ids");
        c.check(ids.size() >= 8, "OverlayThemes lists only " + ids);
        c.check(ids.equals(new ArrayList<>(TABS.keySet())), "the testkit's tab map " + TABS.keySet() + " is out of step with the mod's " + ids);
        Path screens = (Path) Mod.staticCall(ST, "path");
        Path menuPath = (Path) Mod.staticCall("gui.MenuConfig", "path");
        byte[] screensBefore = snapshot(screens);
        byte[] menuBefore = snapshot(menuPath);
        Object menu = Mod.cfg("gui.MenuConfig");
        int savedBefore = ((List<?>) Mod.call(menu, "getSavedThemes")).size();
        try {
            // 1. each overlay: setting present, starts on Default, no palette
            Map<String, AbstractWidget> buttons = new LinkedHashMap<>();
            // A pre-2026-10-18 Inventory Theme file migrates to Vanilla (its old Default meant vanilla colours,
            // InventoryThemeConfig.load); start that one from Default by hand, every other overlay must already be there.
            if (!choice("inventory_theme").equals("DEFAULT")) {
                c.note("inventory_theme starts on " + choice("inventory_theme") + " (a migrated file); set to Default");
                Mod.staticCall(ST, "set", "inventory_theme", "DEFAULT");
            }
            for (String id : ids) {
                c.check(choice(id).equals("DEFAULT"), id + " does not start on Default: " + choice(id));
                c.check(Mod.staticCall(OT, "of", id) == null, id + " has a palette while on Default");
                AbstractWidget b = button(c, id);
                buttons.put(id, b);
                c.check(text(b).endsWith(": " + TABS.get(id)[3]), id + "'s button reads '" + text(b) + "', not "
                        + TABS.get(id)[3]);
            }
            c.note("overlays with a Menu Theme setting, all on Default: " + ids);

            // 2. a press steps to Amber, and presets change what is drawn
            for (String id : ids) {
                int base = drawn(id);
                AbstractWidget b = buttons.get(id);
                press(c, b);
                c.check(choice(id).equals("AMBER"), id + ": one press gave " + choice(id));
                String afterPress = text(button(c, id));
                c.check(afterPress.endsWith(": Amber"), id + ": button reads '" + afterPress + "' after a press");
                int amber = drawn(id);
                Mod.staticCall(ST, "set", id, "OCEAN");
                int ocean = drawn(id);
                Mod.staticCall(ST, "set", id, "LIGHT");
                int light = drawn(id);
                c.note(String.format("%s: panel Default %08X, Amber %08X, Ocean %08X, Light %08X", id, base, amber, ocean, light));
                c.check(Mod.staticCall(OT, "of", id) != null, id + " has no palette on Light");
                c.check(amber != ocean && ocean != light && amber != light,
                        id + ": presets do not draw different colors (" + Integer.toHexString(amber) + ", "
                                + Integer.toHexString(ocean) + ", " + Integer.toHexString(light) + ")");
                if (id.equals("storage_overlay")) {
                    c.check(base != amber && base != ocean && base != light, id + ": Default draws the same as a menu preset");
                }
                if (id.equals("inventory_theme")) {
                    // Since mod 4a7ffc31 the Inventory Theme's Default follows the mod menu's theme: it draws what the
                    // menu's own theme, picked by name, draws.
                    String menuTheme = String.valueOf(Mod.call(menu, "getTheme"));
                    if (!menuTheme.equals("CUSTOM")) {
                        Mod.staticCall(ST, "set", id, menuTheme);
                        int asMenu = drawn(id);
                        c.check(asMenu == base, id + ": Default draws " + Integer.toHexString(base) + ", the menu's theme "
                                + menuTheme + " draws " + Integer.toHexString(asMenu));
                    } else {
                        c.note(id + ": the menu is on Custom, so Default-follows-the-menu is not compared to a preset");
                    }
                }
                Mod.staticCall(ST, "set", id, "DEFAULT");
                c.check(drawn(id) == base, id + ": back on Default draws " + Integer.toHexString(drawn(id)) + ", was " + Integer.toHexString(base));
            }

            // 3. a custom theme made now is in every list and draws its own colours
            Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "PANEL"), 0x123456);
            Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "ACCENT"), 0xE0E010);
            int idx = (Integer) Mod.call(menu, "saveCurrentAsTheme");
            Object saved = ((List<?>) Mod.call(menu, "getSavedThemes")).get(idx);
            String savedId = (String) Mod.field(saved, "id");
            String savedChoice = "SAVED:" + savedId.toUpperCase();
            c.check(!savedId.isBlank(), "the saved theme has no id");
            @SuppressWarnings("unchecked")
            List<String> all = (List<String>) Mod.staticCall(ST, "builtInChoices");
            c.check(all.contains(savedChoice), "the saved theme " + savedChoice + " is not in the choice list " + all);
            c.note("choice list after saving a theme: " + all);
            for (String id : ids) {
                AbstractWidget b = buttons.get(id);
                // press (right-click would go back; left forward) until it lands on the saved theme: bounded by the list
                boolean reached = false;
                for (int i = 0; i < all.size() + 1 && !reached; i++) {
                    press(c, b);
                    reached = choice(id).equals(savedChoice);
                }
                c.check(reached, id + ": pressing the button never reached the saved theme (stopped on " + choice(id) + ")");
                String onSavedLabel = text(button(c, id));
                c.check(onSavedLabel.endsWith(": " + MenuShownName(saved)), id + ": button reads '" + onSavedLabel
                        + "' on the saved theme");
                Object pal = Mod.staticCall(OT, "of", id);
                c.check(pal != null, id + " has no palette on the saved theme");
                int panelBase = ((int[]) Mod.call(pal, "base"))[1] & 0xFFFFFF;
                c.check(panelBase == 0x123456, id + ": palette panel base " + Integer.toHexString(panelBase) + " is not the saved 123456");
                int onSaved = drawn(id);
                // recolour the saved theme while it is in use: the overlay follows
                Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "PANEL"), 0x654321);
                int recoloured = drawn(id);
                c.check(recoloured != onSaved, id + ": recoloring the saved theme did not change what is drawn");
                Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "PANEL"), 0x123456);
                c.check(drawn(id) == onSaved, id + ": restoring the color did not restore what is drawn");
                Mod.staticCall(ST, "set", id, "AMBER");
                c.check(drawn(id) != onSaved, id + ": the saved theme draws the same as Amber");
                Mod.staticCall(ST, "set", id, savedChoice);
            }

            // 4. a rename shows; a save and reload keep the choice; a delete falls back to Default
            Mod.call(menu, "renameSavedTheme", idx, "Sunset");
            for (String id : ids) {
                Mod.staticCall(ST, "set", id, savedChoice);
                String label = (String) Mod.staticCall(ST, "label", savedChoice, true);
                c.check(label.equals("Sunset"), id + ": the renamed theme's label is '" + label + "'");
            }
            Mod.call(menu, "save");
            Mod.staticCall(ST, "save");
            Mod.staticCall("gui.MenuConfig", "load");
            Mod.staticCall(ST, "load");
            menu = Mod.cfg("gui.MenuConfig");
            c.check(((List<?>) Mod.call(menu, "getSavedThemes")).size() == savedBefore + 1, "the saved theme did not survive a reload");
            for (String id : ids) {
                c.check(choice(id).equals(savedChoice), id + ": the choice did not survive a reload: " + choice(id));
                c.check(Mod.staticCall(OT, "of", id) != null, id + ": no palette after a reload");
            }
            int at = ((List<?>) Mod.call(menu, "getSavedThemes")).size() - 1;
            Mod.call(menu, "deleteSavedTheme", at);
            for (String id : ids) {
                c.check(Mod.staticCall(OT, "of", id) == null, id + ": a deleted theme still draws");
                c.check(((String) Mod.staticCall(ST, "label", choice(id), true)).equals("Default"),
                        id + ": a deleted theme's label is not Default");
            }

            // 5. a menu screen's own Theme setting offers the same list (Termism)
            Mod.call(menu, "setCustomColour", Mod.enumValue("gui.MenuTheme$Slot", "TEXT"), 0x00FF00);
            int idx2 = (Integer) Mod.call(menu, "saveCurrentAsTheme");
            String choice2 = (String) Mod.staticCall(ST, "savedChoice", idx2);
            c.check(((List<?>) Mod.staticCall(ST, "builtInChoices")).contains(choice2), "second saved theme not in the list");
            Mod.staticCall(ST, "set", "termism", choice2);
            int[] cols = (int[]) Mod.staticCall("gui.MenuTheme", "forScreen", "termism");
            c.check((cols[3] & 0xFFFFFF) == 0x00FF00, "Termism's colors on the saved theme: text " + Integer.toHexString(cols[3]));
            Mod.call(menu, "deleteSavedTheme", idx2);
            int[] fallback = (int[]) Mod.staticCall("gui.MenuTheme", "forScreen", "termism");
            int[] menuNow = (int[]) Mod.call(Mod.cfg("gui.MenuConfig"), "activeColours");
            c.check(java.util.Arrays.equals(fallback, menuNow), "a deleted theme should read as Theme Default (the menu's colors)");
            c.check(((String) Mod.staticCall(ST, "label", choice("termism"))).equals("Theme Default"),
                    "a deleted theme's label on a menu screen is not Theme Default");
        } finally {
            restore(screens, screensBefore);
            restore(menuPath, menuBefore);
            Mod.staticCall("gui.MenuConfig", "load");
            Mod.staticCall(ST, "load");
        }
    }

    private static String MenuShownName(Object saved) {
        return (String) Mod.staticCall("gui.MenuConfig", "shownName", saved);
    }
}
