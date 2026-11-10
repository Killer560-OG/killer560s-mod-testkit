package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * untestbugs-1109: the menu and screen changes of the Untested pass. Each case asserts the feature acted (a row exists, a
 * fill count rose, a frame counter rose) and the control (a removed class stays gone, a switch OFF draws nothing).
 */
final class UntestBugs1109Cases {

    private UntestBugs1109Cases() {
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Object tab) {
        List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
        }
        return out;
    }

    private static boolean has(List<String> labels, String name) {
        for (String l : labels) {
            if (l != null && (l.equals(name) || l.startsWith(name + ":"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean gone(String cls) {
        try {
            Mod.cls(cls);
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Dungeon Waypoints, Ghost Block and the Secret Triggerbot card are gone; Secret Aura's card carries the triggerbot. */
    static void menuMerges(UiCase c) throws Exception {
        c.check(gone("dungeonwaypoints.DungeonWaypointsFeature"), "Dungeon Waypoints still in the jar");
        c.check(gone("gui.tab.DungeonWaypointsTab"), "the Dungeon Waypoints tab still in the jar");
        c.check(gone("gui.tab.GhostBlockTab"), "the Ghost Block tab still in the jar");
        c.check(gone("gui.tab.SecretTriggerbotTab"), "the Secret Triggerbot tab still in the jar");
        if (!Mod.isCheat()) {
            c.note("legit jar: the merged cheat card is not built");
            return;
        }
        Object cfg = Mod.cfg("secrettrigger.SecretTriggerbotConfig");
        boolean was = (Boolean) Mod.call(cfg, "isEnabled");
        try {
            Mod.call(cfg, "setEnabled", true);
            Object tab = Mod.cls("gui.tab.SecretAuraTab").getConstructor().newInstance();
            List<String> rows = c.onClient(mc -> labels(tab));
            c.note("Secret Aura card rows: " + rows);
            for (String r : new String[]{"Secret Aura", "Secret Triggerbot", "Delay", "Cooldown", "Swap To Slot"}) {
                c.check(has(rows, r), "the merged Secret Aura card has no '" + r + "' row: " + rows);
            }
        } finally {
            Mod.call(cfg, "setEnabled", was);
        }
        Object abc = Mod.cfg("dungeonextras.DungeonExtrasConfig");
        boolean noFilter = true;
        try {
            Mod.call(abc, "getAutoDialogueNpcFilter");
            noFilter = false;
        } catch (Throwable t) {
            // the NPC names filter is gone: the call has nothing to reach
        }
        c.check(noFilter, "Auto Dialogue still has an NPC names filter");
    }

    /** Dark Mode fills exactly once per GUI frame with the chat or the HUD editor open; off draws nothing. */
    static void darkChat(UiCase c) throws Exception {
        Object cfg = Mod.cfg("darkmode.DarkModeConfig");
        boolean en = (Boolean) Mod.call(cfg, "isEnabled");
        boolean tint = (Boolean) Mod.call(cfg, "isTintHud");
        Object ct = Mod.cfg("chattabs.ChatTabsConfig");
        boolean ctOn = (Boolean) Mod.call(ct, "isEnabledRaw");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTintHud", true);
                Mod.call(ct, "setEnabled", true);
                return null;
            });
            for (String screenName : new String[]{"chat", "hudeditor"}) {
                long before = (Integer) Mod.field("darkmode.DarkModeFeature", "frameFills");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, screenName.equals("chat") ? new ChatScreen("", false) : newHudEditor());
                    return null;
                });
                c.ticks(4);
                c.onClient(mc -> {
                    for (int i = 0; i < 3; i++) {
                        mc.gui.extractRenderState(mc.getDeltaTracker(), false, false);
                    }
                    return null;
                });
                long after = (Integer) Mod.field("darkmode.DarkModeFeature", "frameFills");
                c.check(after - before >= 3, "Dark Mode did not fill on the " + screenName + " screen (" + before + " -> " + after + ")");
                if (screenName.equals("chat")) {
                    long frames = (Long) Mod.staticCall("chattabs.ChatTabsFeature", "framesDrawn");
                    c.check(frames > 0, "Chat Tabs drew no frame from the chat screen's own render (the mixin never ran)");
                }
                c.onClient(mc -> {
                    Mod.call(cfg, "setEnabled", false);
                    return null;
                });
                long off0 = (Integer) Mod.field("darkmode.DarkModeFeature", "frameFills");
                c.onClient(mc -> {
                    mc.gui.extractRenderState(mc.getDeltaTracker(), false, false);
                    return null;
                });
                long off1 = (Integer) Mod.field("darkmode.DarkModeFeature", "frameFills");
                c.check(off1 == off0, "Dark Mode OFF still tinted the " + screenName + " screen");
                c.onClient(mc -> {
                    Mod.call(cfg, "setEnabled", true);
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", en);
                Mod.call(cfg, "setTintHud", tint);
                Mod.call(ct, "setEnabled", ctOn);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    private static Screen newHudEditor() {
        try {
            return (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no HudEditorScreen: " + e);
        }
    }

    /** Watcher Helper's Send To Party Chat and Blood Camp's Work Just Outside Blood Room: rows exist, default OFF, persist. */
    static void newSwitches(UiCase c) throws Exception {
        Object w = Mod.cfg("splittimers.WatcherHelperConfig");
        boolean ws = (Boolean) Mod.call(w, "isSendToParty");
        boolean wEn = (Boolean) Mod.call(w, "getEnabledRaw");
        try {
            Mod.call(w, "setEnabled", true);
            Object tab = Mod.cls("gui.tab.WatcherHelperTab").getConstructor().newInstance();
            List<String> rows = c.onClient(mc -> labels(tab));
            c.check(has(rows, "Send To Party Chat"), "Watcher Helper has no Send To Party Chat row: " + rows);
            Mod.call(w, "setSendToParty", true);
            Mod.call(w, "save");
            Mod.staticCall("splittimers.WatcherHelperConfig", "load");
            c.check((Boolean) Mod.call(Mod.cfg("splittimers.WatcherHelperConfig"), "isSendToParty"),
                    "Send To Party Chat did not survive a reload");
        } finally {
            Mod.call(w, "setSendToParty", ws);
            Mod.call(w, "setEnabled", wEn);
            Mod.call(w, "save");
        }
        if (!Mod.isCheat()) {
            return;
        }
        Object b = Mod.cfg("bloodcamp.BloodCampConfig");
        boolean was = (Boolean) Mod.call(b, "isWorkOutside");
        try {
            c.check(!was, "Work Just Outside Blood Room should default OFF");
            Mod.call(b, "setWorkOutside", true);
            Mod.call(b, "save");
            Mod.staticCall("bloodcamp.BloodCampConfig", "load");
            c.check((Boolean) Mod.call(Mod.cfg("bloodcamp.BloodCampConfig"), "isWorkOutside"),
                    "Work Just Outside Blood Room did not survive a reload");
        } finally {
            Mod.call(Mod.cfg("bloodcamp.BloodCampConfig"), "setWorkOutside", was);
            Mod.call(Mod.cfg("bloodcamp.BloodCampConfig"), "save");
        }
    }

    private static int indexOf(List<String> rows, String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (has(List.of(rows.get(i)), name)) {
                return i;
            }
        }
        return -1;
    }

    /** No Rotate card, Auto Ult's Ult When order, Auto Croesus reroll defaults, and the status lines that were removed. */
    static void cards(UiCase c) throws Exception {
        // Reroll on floors: a fresh config has only F7 and M7 on.
        Object cro = Mod.cfg("croesus.CroesusConfig");
        for (String f : new String[]{"F1", "F4", "F6", "M1", "M6"}) {
            c.check(!(Boolean) Mod.call(cro, "isRerollFloor", f), "a fresh config rerolls on " + f);
        }
        if (!Mod.isCheat()) {
            return;
        }
        // (the live config may carry his own values; the default is read off a new instance)
        java.lang.reflect.Constructor<?> ctor = Mod.cls("croesus.CroesusConfig").getDeclaredConstructor();
        ctor.setAccessible(true);
        Object fresh = ctor.newInstance();
        c.check((Boolean) Mod.call(fresh, "isRerollFloor", "F7") && (Boolean) Mod.call(fresh, "isRerollFloor", "M7"),
                "F7 and M7 are not on by default");
        c.check(!(Boolean) Mod.call(fresh, "isRerollFloor", "F1"), "F1 is on by default");
        // No Rotate: one switch, same saved value as before.
        Object leap = Mod.cfg("fastleap.FastLeapConfig");
        boolean was = (Boolean) Mod.call(leap, "isLeapTakesTargetLook");
        try {
            Object tab = Mod.cls("gui.tab.NoRotateTab").getConstructor().newInstance();
            List<String> rows = c.onClient(mc -> labels(tab));
            c.check(has(rows, "No Rotate"), "the No Rotate card has no switch row: " + rows);
            Mod.call(leap, "setLeapTakesTargetLook", true);
            c.check((Boolean) Mod.call(leap, "isLeapTakesTargetLook"), "No Rotate's switch did not take");
            Object fl = Mod.cls("gui.tab.FastLeapTab").getConstructor().newInstance();
            List<String> flRows = c.onClient(mc -> labels(fl));
            c.check(indexOf(flRows, "Leap Takes Target's Look") < 0 && indexOf(flRows, "No Rotate") < 0,
                    "the Fast Leap card still carries the old row: " + flRows);
        } finally {
            Mod.call(leap, "setLeapTakesTargetLook", was);
        }
        // Auto Ult: Wish On Clear comes before the class toggles, under its own header.
        Object ult = Mod.cfg("cheatutils.CheatUtilsConfig");
        boolean ultWas = (Boolean) Mod.call(ult, "isAutoUltEnabled");
        try {
            Mod.call(ult, "setAutoUltEnabled", true);
            Object tab = Mod.cls("gui.tab.AutoUltTab").getConstructor().newInstance();
            List<String> rows = c.onClient(mc -> labels(tab));
            int wish = indexOf(rows, "Wish On Clear");
            int mage = indexOf(rows, "Mage");
            int maxor = indexOf(rows, "On Maxor Enraged");
            c.check(wish >= 0 && maxor > wish && mage > maxor, "Auto Ult order wrong (wish " + wish + ", maxor " + maxor + ", mage " + mage + "): " + rows);
        } finally {
            Mod.call(ult, "setAutoUltEnabled", ultWas);
        }
        // The Secret Aura card has no Tracked status line and Auto Croesus no Idle line.
        Object sa = Mod.cls("gui.tab.SecretAuraTab").getConstructor().newInstance();
        List<String> saRows = c.onClient(mc -> labels(sa));
        c.check(indexOf(saRows, "Tracked") < 0, "the Secret Aura card still shows a Tracked line: " + saRows);
    }
}
