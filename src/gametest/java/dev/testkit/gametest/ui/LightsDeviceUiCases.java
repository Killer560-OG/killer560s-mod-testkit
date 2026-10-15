package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1475-ui-lights-device (mod lights-aura-1015, killer560 2026-10-15: "Make lights have its own aura trigger and own
 * triggerbot stuff it should not be impacted by other auras. It should be its own cheat."):
 * <ul>
 *   <li>Legit jar: no Lights Device card anywhere in the menu.</li>
 *   <li>Cheat jar: exactly one Lights Device card, in the Dungeons category, cheat-only (red, the cheat block); its tab
 *       holds the Lights Aura and Lights Triggerbot switches, each half's settings show only under its own switch, and the
 *       card stands for both switches. Secret Aura's In Boss no longer builds any Lights row and keeps its S2 Section
 *       Levers. Every row has its own scoped tooltip.</li>
 *   <li>Every setting survives a save and a reload.</li>
 *   <li>Migration: an In Boss (leveraura.json) file with its old Lights keys hands them over once - Aura ON when In Boss
 *       was on with a Lights option on, OFF when In Boss was off, the range/CPS/swing/chat it ran with kept - and is
 *       rewritten without them (its section-lever keys kept); a pre-merge file with "enabled": true turns both In Boss
 *       and the Aura on; a second load changes nothing.</li>
 * </ul>
 */
final class LightsDeviceUiCases {

    private static final String TAB = "gui.tab.LightsDeviceTab";
    private static final String LD = "lightsdevice.LightsDeviceConfig";
    private static final String LA = "leveraura.LeverAuraConfig";
    private static final String CU = "cheatutils.CheatUtilsConfig";

    private LightsDeviceUiCases() {
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

    static void lightsDevice(UiCase c) throws Exception {
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            c.onClient(mc -> {
                ModScreenDriver.flatten(d.tree(), flat);
                return null;
            });
            List<ModScreenDriver.Node> cards = new ArrayList<>();
            for (ModScreenDriver.Node n : flat) {
                if (!n.folder() && n.name().equals("Lights Device")) {
                    cards.add(n);
                }
            }
            MenuLayoutCases.close(c, d);
            if (!Mod.isCheat()) {
                c.note("legit jar: Lights Device cards " + cards.size());
                c.check(cards.isEmpty(), "the legit jar shows a Lights Device card at " + cards.get(0).path());
                return;
            }
            c.check(cards.size() == 1, "the cheat jar should have one Lights Device card, found " + cards.size());
            ModScreenDriver.Node card = cards.get(0);
            c.note("Lights Device card at " + card.path());
            c.check(card.path().startsWith("Dungeons > "), "the Lights Device card is not in the Dungeons category: " + card.path());
            Object tab = card.tab();
            c.check(tab.getClass().getName().endsWith(TAB.substring(TAB.lastIndexOf('.'))), "the card's tab is " + tab.getClass());
            c.check((Boolean) Mod.call(tab, "isCheatOnly"), "the Lights Device tab is not cheat-only");

            Path ldPath = (Path) Mod.field(LD, "CONFIG_PATH");
            Path laPath = (Path) Mod.field(LA, "CONFIG_PATH");
            Path cuPath = (Path) Mod.field(CU, "CONFIG_PATH");
            byte[] ldBefore = snapshot(ldPath);
            byte[] laBefore = snapshot(laPath);
            byte[] cuBefore = snapshot(cuPath);
            try {
                Object ld = Mod.cfg(LD);
                // ---- the tab ----
                Mod.call(ld, "setAuraEnabled", false);
                Mod.call(ld, "setTriggerEnabled", false);
                List<String> off = c.onClient(mc -> labels(build(tab)));
                c.note("both off: " + off);
                c.check(hasRow(off, "Lights Aura") && hasRow(off, "Lights Triggerbot"), "both switches should show: " + off);
                c.check(!hasRow(off, "Pre-Flick Before S2") && !hasRow(off, "Triggerbot Delay"),
                        "a half's settings show with its switch off: " + off);
                Mod.call(ld, "setAuraEnabled", true);
                List<String> aura = c.onClient(mc -> labels(build(tab)));
                c.note("Aura on: " + aura);
                String[] auraRows = {"Pre-Flick Before S2", "Finish When S2 Opens", "Aura Range", "Aura CPS", "Aura Swing Hand",
                        "Chat Feedback"};
                for (String r : auraRows) {
                    c.check(hasRow(aura, r), "Aura on but no '" + r + "' row: " + aura);
                }
                c.check(!hasRow(aura, "Triggerbot Delay"), "Triggerbot rows show with only the Aura on: " + aura);
                Mod.call(ld, "setAuraEnabled", false);
                Mod.call(ld, "setTriggerEnabled", true);
                List<String> trig = c.onClient(mc -> labels(build(tab)));
                c.note("Triggerbot on: " + trig);
                String[] trigRows = {"Triggerbot Delay", "Triggerbot Cooldown", "Triggerbot Swing Hand"};
                for (String r : trigRows) {
                    c.check(hasRow(trig, r), "Triggerbot on but no '" + r + "' row: " + trig);
                }
                c.check(!hasRow(trig, "Aura CPS"), "Aura rows show with only the Triggerbot on: " + trig);
                Object ms = c.onClient(mc -> Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { }));
                c.check(ms != null && ((List<?>) Mod.call(ms, "parts")).size() == 2,
                        "the Lights Device card should stand for two switches (Lights Aura, Lights Triggerbot)");
                for (String r : new String[]{"Lights Aura", "Lights Triggerbot", "Pre-Flick Before S2", "Finish When S2 Opens",
                        "Aura Range", "Aura CPS", "Aura Swing Hand", "Chat Feedback", "Triggerbot Delay", "Triggerbot Cooldown",
                        "Triggerbot Swing Hand"}) {
                    Object tip = Mod.staticCall("gui.SettingTooltips", "describeScoped", "Lights Device", null, r);
                    c.check(tip != null && String.valueOf(tip).length() > 20, "no tooltip for Lights Device > " + r);
                }
                // In Boss keeps its section levers and has no Lights rows.
                Object cu = Mod.cfg(CU);
                Mod.call(cu, "setAuraBossLevers", true);
                Object sa = Mod.cls("gui.tab.SecretAuraTab").getConstructor().newInstance();
                List<String> inBoss = c.onClient(mc -> labels(build(sa)));
                c.note("Secret Aura with In Boss on: " + inBoss);
                c.check(hasRow(inBoss, "S2 Section Levers") && hasRow(inBoss, "Boss Lever CPS"),
                        "In Boss lost its section-lever rows: " + inBoss);
                for (String l : inBoss) {
                    c.check(l == null || !l.toLowerCase(java.util.Locale.ROOT).contains("light"),
                            "Secret Aura still builds a Lights row: " + l);
                }

                // ---- every setting survives a save and a reload ----
                Mod.call(ld, "setAuraEnabled", true);
                Mod.call(ld, "setTriggerEnabled", true);
                Mod.call(ld, "setPreFlick", false);
                Mod.call(ld, "setFinish", false);
                Mod.call(ld, "setRange", 3.3);
                Mod.call(ld, "setCps", 13);
                Mod.call(ld, "setAuraSwingHand", false);
                Mod.call(ld, "setChatFeedback", false);
                Mod.call(ld, "setTriggerDelayMs", 125);
                Mod.call(ld, "setTriggerCooldownMs", 650);
                Mod.call(ld, "setTriggerSwingHand", false);
                Mod.call(ld, "save");
                Mod.staticCall(LD, "load");
                Object re = Mod.cfg(LD);
                c.check(re != ld, "load did not make a new instance");
                c.check((Boolean) Mod.call(re, "isAuraEnabledRaw") && (Boolean) Mod.call(re, "isTriggerEnabledRaw"),
                        "the switches did not survive a reload");
                c.check(!(Boolean) Mod.call(re, "isPreFlick") && !(Boolean) Mod.call(re, "isFinish"), "Pre-Flick/Finish did not survive");
                c.check(Math.abs((Double) Mod.call(re, "getRange") - 3.3) < 0.01, "range: " + Mod.call(re, "getRange"));
                c.check((Integer) Mod.call(re, "getCps") == 13, "cps: " + Mod.call(re, "getCps"));
                c.check(!(Boolean) Mod.call(re, "isAuraSwingHand") && !(Boolean) Mod.call(re, "isChatFeedback")
                        && !(Boolean) Mod.call(re, "isTriggerSwingHand"), "a swing/chat setting did not survive");
                c.check((Integer) Mod.call(re, "getTriggerDelayMs") == 125 && (Integer) Mod.call(re, "getTriggerCooldownMs") == 650,
                        "delay/cooldown: " + Mod.call(re, "getTriggerDelayMs") + "/" + Mod.call(re, "getTriggerCooldownMs"));
                c.note("save + reload: all eleven settings kept");

                // ---- migration from In Boss ----
                // (a) In Boss on, Pre-Flick on, Finish off: the Aura comes on with what it ran with.
                Files.deleteIfExists(ldPath);
                Mod.staticCall(LD, "load");
                Mod.call(Mod.cfg(CU), "setAuraBossLevers", true);
                Mod.call(Mod.cfg(CU), "save");
                Files.createDirectories(laPath.getParent());
                Files.writeString(laPath, "{\"lightsPreFlick\": true, \"lightsFinish\": false, \"sectionLevers\": true,"
                        + " \"range\": 3.0, \"cps\": 7, \"swingHand\": false, \"chatFeedback\": false}", StandardCharsets.UTF_8);
                Mod.staticCall(LA, "load");
                Object m = Mod.cfg(LD);
                c.check((Boolean) Mod.call(m, "isAuraEnabledRaw"), "In Boss on with Pre-Flick on: the Aura should be ON");
                c.check(!(Boolean) Mod.call(m, "isTriggerEnabledRaw"), "a migration turned the Triggerbot on");
                c.check((Boolean) Mod.call(m, "isPreFlick") && !(Boolean) Mod.call(m, "isFinish"), "Pre-Flick/Finish not carried");
                c.check(Math.abs((Double) Mod.call(m, "getRange") - 3.0) < 0.01 && (Integer) Mod.call(m, "getCps") == 7,
                        "range/cps not carried: " + Mod.call(m, "getRange") + "/" + Mod.call(m, "getCps"));
                c.check(!(Boolean) Mod.call(m, "isAuraSwingHand") && !(Boolean) Mod.call(m, "isChatFeedback"),
                        "swing/chat not carried");
                String la = Files.readString(laPath, StandardCharsets.UTF_8);
                c.check(!la.contains("lightsPreFlick") && !la.contains("lightsFinish"), "the In Boss file kept its Lights keys: " + la);
                c.check(la.contains("\"sectionLevers\": true") && la.contains("\"cps\": 7"), "the In Boss file lost its own keys: " + la);
                c.check(Files.exists(ldPath) && Files.readString(ldPath, StandardCharsets.UTF_8).contains("\"auraEnabled\": true"),
                        "the migrated Lights Device file was not saved");
                Mod.staticCall(LD, "load");
                c.check((Boolean) Mod.call(Mod.cfg(LD), "isAuraEnabledRaw"), "a reload of the Lights Device lost the migration");
                // a second In Boss load changes nothing: turn the Aura off, reload In Boss, it stays off
                Mod.call(Mod.cfg(LD), "setAuraEnabled", false);
                Mod.call(Mod.cfg(LD), "save");
                Mod.staticCall(LA, "load");
                c.check(!(Boolean) Mod.call(Mod.cfg(LD), "isAuraEnabledRaw"), "a second In Boss load migrated again");

                // (b) In Boss off: the options carry, the Aura stays off.
                Files.deleteIfExists(ldPath);
                Mod.staticCall(LD, "load");
                Mod.call(Mod.cfg(CU), "setAuraBossLevers", false);
                Files.writeString(laPath, "{\"lightsPreFlick\": false, \"lightsFinish\": true, \"cps\": 5}", StandardCharsets.UTF_8);
                Mod.staticCall(LA, "load");
                m = Mod.cfg(LD);
                c.check(!(Boolean) Mod.call(m, "isAuraEnabledRaw"), "In Boss off: the Aura should stay OFF");
                c.check(!(Boolean) Mod.call(m, "isPreFlick") && (Boolean) Mod.call(m, "isFinish") && (Integer) Mod.call(m, "getCps") == 5,
                        "In Boss off: the options should still carry");

                // (c) a pre-merge Lever Aura file, "enabled": true with its Lights options: In Boss and the Aura both on.
                Files.deleteIfExists(ldPath);
                Mod.staticCall(LD, "load");
                Mod.call(Mod.cfg(CU), "setAuraBossLevers", false);
                Mod.call(Mod.cfg(CU), "save");
                Files.writeString(laPath, "{\"enabled\": true, \"lightsPreFlick\": true, \"lightsFinish\": true}", StandardCharsets.UTF_8);
                Mod.staticCall(LA, "load");
                c.check((Boolean) Mod.call(Mod.cfg(CU), "isAuraBossLevers"), "enabled:true did not turn In Boss on");
                c.check((Boolean) Mod.call(Mod.cfg(LD), "isAuraEnabledRaw"), "enabled:true with Lights on did not turn the Aura on");
                c.note("migration: In Boss on -> Aura on with range/cps/swing/chat; In Boss off -> options only; enabled:true -> both");
            } finally {
                restore(ldPath, ldBefore);
                restore(laPath, laBefore);
                restore(cuPath, cuBefore);
                Mod.staticCall(CU, "load");
                Mod.staticCall(LA, "load");
                Mod.staticCall(LD, "load");
            }
        }
    }
}
