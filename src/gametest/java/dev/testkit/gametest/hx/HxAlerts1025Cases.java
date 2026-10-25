package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Dungeon alerts of 2026-10-25 (branch dungeon-alerts-1025): Teammate Low Health Alert, Livid extras, the pre-boss
 * score warning and crypt reminder, secret feedback, the Dungeon Breaker charges HUD and the Wither Key spawn alert.
 * Each case proves the feature ACTED (an alert counter moved and the title on screen says so, a HUD drew, a command
 * reached the server) and that it stays quiet where it must.
 */
final class HxAlerts1025Cases {

    static final String ALERTS_CFG = "dungeonalerts.DungeonAlertsConfig";
    static final String LOW_HEALTH = "dungeonalerts.LowHealthAlert";

    private HxAlerts1025Cases() {
    }

    static void register(Session s) {
        s.test("1025-alerts-lowhealth", HxAlerts1025Cases::lowHealth);
    }

    static String guiTitle(Minecraft mc) {
        try {
            Field f = mc.gui.getClass().getDeclaredField("title");
            f.setAccessible(true);
            Object t = f.get(mc.gui);
            return t == null ? null : ((Component) t).getString();
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** The F7 dungeon sidebar with the two mates' health lines (Hypixel's shape: class letter, name, colored number). */
    private static void mates(Session c, String healthA, String healthB) {
        List<String> extra = new ArrayList<>();
        extra.add("§e[M] §bHxMateA " + healthA);
        extra.add("§e[B] §6HxMateB " + healthB);
        sidebar(c, "template", "dungeon", "floor", "F7", "extra", extra);
    }

    private static int fired(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired"));
    }

    // ---- 1025-alerts-lowhealth -----------------------------------------------------------------------------------

    private static void lowHealth(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "LowHealthEnabled", true)
                .with(ALERTS_CFG, "LowHealthOnlyHealer", false)
                .with(ALERTS_CFG, "LowHealthSound", false)) {
            dungeon(c);
            mates(c, "§a25,000§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int base = fired(c);
            c.ctx().waitTicks(20);
            c.check(fired(c) == base, "an alert fired with both mates on green health");

            mates(c, "§c2,900§c❤", "§a30,100§c❤");
            c.waitUntil("the alert fires when HxMateA's health turns red", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 1, 40);
            String title = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(title != null && title.contains("HxMateA") && title.contains("is low"), "alert title was '" + title + "'");
            c.check(shown != null && shown.contains("HxMateA"), "the on-screen title is '" + shown + "'");

            // Still red, a new number: no second alert until HxMateA has left red.
            mates(c, "§c1,200§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int stillRed = fired(c);
            // Dead is not "low".
            mates(c, "§cDEAD", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int dead = fired(c);
            c.check(stillRed == base + 1, "a second alert fired while HxMateA stayed red (" + stillRed + ")");
            c.check(dead == base + 1, "an alert fired for a DEAD teammate (" + dead + ")");

            // Only While Healer: the client is Archer in the tab list, so HxMateB going red says nothing ...
            run(c, () -> Mod.call(Mod.cfg(ALERTS_CFG), "setLowHealthOnlyHealer", true));
            mates(c, "§a20,000§c❤", "§c4,000§c❤");
            c.ctx().waitTicks(25);
            int asArcher = fired(c);
            c.check(asArcher == base + 1, "an alert fired with Only While Healer on while playing Archer (" + asArcher + ")");
            // ... until the tab list says Healer.
            tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", "[MVP+]"), player("HxMateB", "Berserk", null),
                    player(self(c), "Healer", null)));
            c.waitUntil("PartyTracker.selfClass() == HEALER", mc -> "HEALER".equals(String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "selfClass"))), 60);
            c.waitUntil("the alert fires for HxMateB once you are Healer", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 2, 40);
            String title2 = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            c.check(title2 != null && title2.contains("HxMateB"), "second alert title was '" + title2 + "'");
            c.note("green: none; A red -> '" + title + "' (Gui '" + shown + "'); still red / DEAD: none; Healer-only as Archer: none;"
                    + " as Healer -> '" + title2 + "'");
        } finally {
            dungeon(c);
        }
    }
}
