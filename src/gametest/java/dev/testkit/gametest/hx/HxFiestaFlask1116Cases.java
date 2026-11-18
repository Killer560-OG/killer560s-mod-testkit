package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.InteractionHand;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Fiesta Flask Timer (mod fiestaflask-1116, miningqol.FiestaFlask):
 * <ul>
 *   <li>1116-fiestaflask-timer: a confirmed start shows "Fiesta Flask: ..." on the HUD (drawn), it counts down with or
 *       without a Mining Fiesta in the tab (never "paused"), survives a reload from the state file,
 *       takes a server-shown time from a tab line, and expires with exactly one alert.</li>
 *   <li>1116-fiestaflask-click: a drink whose stack never shrinks starts nothing; the same drink with the stack
 *       shrinking inside two seconds starts the timer at the set duration.</li>
 * </ul>
 */
final class HxFiestaFlask1116Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String FF = "miningqol.FiestaFlask";
    private static final String FLASK = "minecraft:potion[custom_data={id:\"FIESTA_FLASK\"},item_name=\"Fiesta Flask\"]";

    private HxFiestaFlask1116Cases() {
    }

    static void register(Session s) {
        s.test("1116-fiestaflask-timer", HxFiestaFlask1116Cases::timer);
        s.test("1116-fiestaflask-click", HxFiestaFlask1116Cases::click);
    }

    private static void area(Session c, String... extraTab) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Dwarven Mines", "Purse: 0");
        List<String> tab = new java.util.ArrayList<>(List.of("Area: Dwarven Mines", "Profile: Test", ""));
        tab.addAll(List.of(extraTab));
        c.hx().tab(tab, null, null);
    }

    private static long remaining(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(FF, "remainingMs"));
    }

    private static String text(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(FF, "text"));
    }

    private static int count(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(FF, m)).intValue());
    }

    private static void timer(Session c) {
        try (Settings set = new Settings(c).with(CFG, "FiestaFlask", true).with(CFG, "FiestaFlaskAlert", true)) {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            area(c, "Event: Mining Fiesta");
            c.ctx().waitTicks(30);
            c.check(text(c) == null, "text before any start: " + text(c));
            c.onClient(mc -> Mod.staticCall(FF, "startForTest", System.currentTimeMillis(), 12_000L));
            c.waitUntil("the HUD text", mc -> {
                String t = (String) Mod.staticCall(FF, "text");
                return t != null && t.startsWith("Fiesta Flask: ") && !t.contains("paused");
            }, 40);
            c.waitUntil("the HUD element is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_fiesta_flask"), 60);
            long r0 = remaining(c);
            c.ctx().waitTicks(60);
            long r1 = remaining(c);
            c.note("fiesta shown: " + r0 + " -> " + r1 + " ms, text " + text(c));
            c.check(r1 < r0 - 2000 && r1 > 0, "no countdown while a Mining Fiesta is shown: " + r0 + " -> " + r1);
            // Reload: the saved remaining time comes back.
            long before = remaining(c);
            c.onClient(mc -> Mod.staticCall(FF, "reloadForTest"));
            long after = remaining(c);
            c.check(after > 0 && Math.abs(after - before) < 35_000L && after <= 12_000L, "reload read " + after + " (was " + before + ")");
            // No fiesta in the tab: the countdown keeps running and never says paused.
            area(c);
            long p0 = remaining(c);
            c.ctx().waitTicks(60);
            long p1 = remaining(c);
            c.check(p1 < p0 - 2000 && p1 > 0, "the countdown stopped outside a Mining Fiesta: " + p0 + " -> " + p1);
            c.check(text(c) != null && !text(c).contains("paused"), "text outside a fiesta: " + text(c));
            // A server-shown time wins.
            area(c, "Active Effects:", " Fiesta Flask I 59:30");
            c.waitUntil("the tab time is taken", mc -> {
                long ms = (Long) Mod.staticCall(FF, "remainingMs");
                return ms > 3_500_000L && ms <= 3_570_000L;
            }, 80);
            // Expiry with one alert.
            int alerts = count(c, "alerts");
            area(c, "Event: Mining Fiesta");
            c.onClient(mc -> Mod.staticCall(FF, "startForTest", System.currentTimeMillis(), 2_500L));
            c.waitUntil("expiry", mc -> (Long) Mod.staticCall(FF, "remainingMs") < 0, 120);
            c.check(text(c) == null, "text after expiry: " + text(c));
            c.check(count(c, "alerts") == alerts + 1, "expiry alerts " + (count(c, "alerts") - alerts));
            c.note("counted down, ran outside a fiesta, reloaded, took the tab time, expired with one alert");
        } finally {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            hub(c);
        }
    }

    private static void click(Session c) {
        try (Settings set = new Settings(c).with(CFG, "FiestaFlask", true)) {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            area(c, "Event: Mining Fiesta");
            c.hx().give(0, FLASK);
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            c.ctx().waitTicks(5);
            // A click the server did not act on: the stack stays.
            c.onClient(mc -> Mod.staticCall(FF, "onUse", mc.player, InteractionHand.MAIN_HAND));
            c.ctx().waitTicks(60);
            c.check(count(c, "startedCount") == 0 && remaining(c) < 0, "a click alone started the timer: "
                    + remaining(c));
            c.check(text(c) == null, "a click alone drew text: " + text(c));
            // The same drink once the stack shrinks.
            c.onClient(mc -> Mod.staticCall(FF, "onUse", mc.player, InteractionHand.MAIN_HAND));
            c.ctx().runOnClient(mc -> mc.player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY));
            c.waitUntil("the timer starts", mc -> (Integer) Mod.staticCall(FF, "startedCount") == 1, 40);
            long r = remaining(c);
            c.note("started with " + r + " ms");
            c.check(r > 59L * 60_000L, "the default duration was not used: " + r);
        } finally {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            hub(c);
        }
    }
}
