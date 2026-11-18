package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mining HUD fixes (mod minehud-1117):
 * <ul>
 *   <li>1117-flask-interp: a tab that shows whole minutes does not pull the Fiesta Flask countdown back, the shown time
 *       keeps counting between reads, a reading that really disagrees re-syncs.</li>
 *   <li>1117-flask-ends: the line leaves when the tab stops listing the flask (also outside a Mining Fiesta, where the
 *       countdown is paused) and when the tab reads zero; one expiry alert for the first.</li>
 *   <li>1117-ability-active: "X: Active" from the used line to the expired line, then the cooldown, then nothing.</li>
 * </ul>
 */
final class HxMineHud1117Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String FF = "miningqol.FiestaFlask";
    private static final String ARA = "miningqol.AbilityReadyAlert";

    private HxMineHud1117Cases() {
    }

    static void register(Session s) {
        s.test("1117-flask-interp", HxMineHud1117Cases::interp);
        s.test("1117-flask-ends", HxMineHud1117Cases::ends);
        s.test("1117-ability-active", HxMineHud1117Cases::ability);
        s.test("1117-ability-color", HxMineHud1117Cases::abilityColor);
        s.test("1117-ability-shots", HxMineHud1117Cases::abilityShots);
    }

    private static void area(Session c, String... extraTab) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Dwarven Mines", "Purse: 0");
        List<String> tab = new ArrayList<>(List.of("Area: Dwarven Mines", "Profile: Test", ""));
        tab.addAll(List.of(extraTab));
        c.hx().tab(tab, null, null);
    }

    private static long remaining(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(FF, "remainingMs"));
    }

    private static String text(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(FF, "text"));
    }

    private static void interp(Session c) {
        try (Settings set = new Settings(c).with(CFG, "FiestaFlask", true)) {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            area(c, "Event: Mining Fiesta", "Active Effects:", " Fiesta Flask I 20m");
            c.waitUntil("the 20m reading", mc -> {
                long ms = (Long) Mod.staticCall(FF, "remainingMs");
                return ms > 1_190_000L && ms <= 1_200_000L;
            }, 80);
            long t0 = System.currentTimeMillis();
            long r0 = remaining(c);
            java.util.Set<String> texts = new java.util.LinkedHashSet<>();
            for (int i = 0; i < 12; i++) {
                c.ctx().waitTicks(10);
                texts.add(text(c));
            }
            long elapsed = System.currentTimeMillis() - t0;
            long r1 = remaining(c);
            c.note("20m reading: " + r0 + " -> " + r1 + " after " + elapsed + " ms, texts " + texts);
            c.check(texts.size() >= 4, "the shown time did not count down every second: " + texts);
            c.check(Math.abs((r0 - r1) - elapsed) < 1500, "countdown off the clock: " + (r0 - r1) + " vs " + elapsed);
            // The tab now shows 19m (its rounding of the same time): it must not re-sync.
            long before = remaining(c);
            long b0 = System.currentTimeMillis();
            area(c, "Event: Mining Fiesta", "Active Effects:", " Fiesta Flask I 19m");
            c.ctx().waitTicks(50);
            long expected = before - (System.currentTimeMillis() - b0);
            long after = remaining(c);
            c.check(Math.abs(after - expected) < 3000, "a whole-minute reading re-synced: " + before + " -> " + after
                    + " (expected about " + expected + ")");
            // A reading that really disagrees wins.
            area(c, "Event: Mining Fiesta", "Active Effects:", " Fiesta Flask I 10m");
            c.waitUntil("the 10m reading", mc -> {
                long ms = (Long) Mod.staticCall(FF, "remainingMs");
                return ms > 590_000L && ms <= 600_000L;
            }, 80);
        } finally {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            hub(c);
        }
    }

    private static void ends(Session c) {
        try (Settings set = new Settings(c).with(CFG, "FiestaFlask", true).with(CFG, "FiestaFlaskAlert", true)) {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            // Paused outside a fiesta (the countdown holds, so it never ran out by itself), then the tab drops the flask.
            area(c, "Active Effects:", " Fiesta Flask I 20m");
            c.waitUntil("the reading", mc -> Mod.staticCall(FF, "text") != null, 80);
            c.waitUntil("(paused)", mc -> String.valueOf(Mod.staticCall(FF, "text")).contains("(paused)"), 80);
            int alerts = ((Number) c.onClient(mc -> Mod.staticCall(FF, "alerts"))).intValue();
            area(c);
            c.waitUntil("the line ends", mc -> Mod.staticCall(FF, "text") == null, 140);
            c.check(remaining(c) < 0, "remaining after the tab dropped the flask: " + remaining(c));
            c.check(((Number) c.onClient(mc -> Mod.staticCall(FF, "alerts"))).intValue() == alerts + 1, "expiry alerts");
            // The tab reading zero.
            area(c, "Event: Mining Fiesta", "Active Effects:", " Fiesta Flask I 5m");
            c.waitUntil("the 5m reading", mc -> Mod.staticCall(FF, "text") != null, 80);
            area(c, "Event: Mining Fiesta", "Active Effects:", " Fiesta Flask I 0s");
            c.waitUntil("zero ends it", mc -> Mod.staticCall(FF, "text") == null, 140);
        } finally {
            c.onClient(mc -> Mod.staticCall(FF, "resetForTest"));
            hub(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c, List<String> tab, long now, long readAt) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(ARA, "timerAtForTest", tab, now, readAt)));
    }

    private static void chat(Session c, String raw, long now) {
        c.onClient(mc -> Mod.staticCall(ARA, "chatForTest", raw, List.<String>of(), now));
    }

    private static String plain(List<String> l) {
        return l.isEmpty() ? "" : l.get(0).replaceAll("§.", "").replace("\u0001", "[A]");
    }

    private static void ability(Session c) {
        try (Settings set = new Settings(c).with(CFG, "AbilityAlert", true).with(CFG, "AbilityTimer", true)
                .with(CFG, "AbilitySound", false)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            long t0 = c.onClient(mc -> System.currentTimeMillis()) + 3_600_000L;
            chat(c, "You used your Mining Speed Boost Pickaxe Ability!", t0);
            List<String> a = lines(c, List.of(), t0 + 100, t0 + 5000);
            c.check(plain(a).equals("[A]Mining Speed Boost: Active"), "active line: " + a);
            // The tab already counts the cooldown: still Active while it runs.
            a = lines(c, List.of("Mining Speed Boost: 1m 5s"), t0 + 1000, t0 + 6000);
            c.check(plain(a).equals("[A]Mining Speed Boost: Active") && a.size() == 1, "active hides the cooldown: " + a);
            chat(c, "Your Mining Speed Boost has expired!", t0 + 20_000);
            a = lines(c, List.of(), t0 + 20_000, t0 + 21_000);
            c.check(a.size() == 1 && plain(a).equals("Mining Speed Boost: 45s"), "cooldown after expiry: " + a);
            a = lines(c, List.of(), t0 + 20_000, t0 + 70_000);
            c.check(a.isEmpty(), "line left when ready: " + a);
            // The measured 20 s now gives a countdown.
            chat(c, "You used your Mining Speed Boost Pickaxe Ability!", t0 + 100_000);
            a = lines(c, List.of(), t0 + 100_000, t0 + 105_000);
            c.check(plain(a).equals("[A]Mining Speed Boost: Active 15s"), "learned active time: " + a);
            // An ability with no duration never shows Active.
            chat(c, "You used your Pickobulus Pickaxe Ability!", t0 + 200_000);
            a = lines(c, List.of(), t0 + 200_000, t0 + 201_000);
            c.check(a.stream().noneMatch(s -> s.contains("Active")), "Pickobulus showed Active: " + a);
            c.note("active, hidden cooldown, expiry, cooldown, ready, learned 15s, instant ability");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            hub(c);
        }
    }

    private static int[] colors(Session c, int i) {
        return c.onClient(mc -> (int[]) Mod.staticCall(ARA, "activeColorsForTest", i));
    }

    private static void abilityColor(Session c) {
        final int green = 0xFF55FF55, yellow = 0xFFFFFF55, red = 0xFFFF5555;
        try (Settings set = new Settings(c).with(CFG, "AbilityAlert", true).with(CFG, "AbilityTimer", true)
                .with(CFG, "AbilitySound", false)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            long t0 = c.onClient(mc -> System.currentTimeMillis()) + 3_600_000L;
            // Unknown duration: green "Active", no number.
            chat(c, "You used your Mining Speed Boost Pickaxe Ability!", t0);
            List<String> a = lines(c, List.of(), t0 + 100, t0 + 5000);
            c.check(plain(a).equals("[A]Mining Speed Boost: Active"), "unknown line: " + a);
            int[] k = colors(c, 0);
            c.check(k != null && k[1] == green, "unknown: Active green " + java.util.Arrays.toString(k));
            // Learn 20 s for both abilities, then watch the fade.
            chat(c, "Your Mining Speed Boost has expired!", t0 + 20_000);
            for (String ab : List.of("Maniac Miner", "Mining Speed Boost")) {
                long t1 = t0 + 100_000;
                chat(c, "You used your " + ab + " Pickaxe Ability!", t1 - 20_000);
                chat(c, "Your " + ab + " has expired!", t1);
                chat(c, "You used your " + ab + " Pickaxe Ability!", t1 + 100);
                long[] at = {t1 + 100, t1 + 10_100, t1 + 19_900};
                int[] want = {green, yellow, red};
                for (int i = 0; i < 3; i++) {
                    List<String> l = lines(c, List.of(), at[i], at[i]);
                    int idx = 0;
                    c.check(l.size() >= 1 && l.get(idx).contains(ab + ": Active "), ab + " line " + i + ": " + l);
                    int[] col = colors(c, idx);
                    c.check(col != null && col[0] != green && col[0] != yellow && col[0] != red,
                            ab + " name keeps element color " + java.util.Arrays.toString(col));
                    c.check(col != null && col[1] == green, ab + " Active green " + java.util.Arrays.toString(col));
                    int num = col == null ? 0 : col[2];
                    int tol = i == 2 ? 0x30 : 0x08;
                    boolean ok = Math.abs(((num >> 16) & 255) - ((want[i] >> 16) & 255)) <= (i == 2 ? 0x30 : 8)
                            && Math.abs(((num >> 8) & 255) - ((want[i] >> 8) & 255)) <= (i == 2 ? 0x60 : 8)
                            && Math.abs((num & 255) - (want[i] & 255)) <= (i == 2 ? 0x30 : 8);
                    c.check(ok, ab + " number color at step " + i + ": " + Integer.toHexString(num));
                }
                chat(c, "Your " + ab + " has expired!", t1 + 30_000);
            }
            c.note("Active green, number green/yellow/red at 100/50/near 0 percent for two abilities, unknown duration");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            hub(c);
        }
    }

    /** Real-time pass: a learned 20 s Maniac Miner on the live HUD, a screenshot at full, half and nearly out. */
    @SuppressWarnings("unchecked")
    private static void abilityShots(Session c) {
        try (Settings set = new Settings(c).with(CFG, "AbilityAlert", true).with(CFG, "AbilityTimer", true)
                .with(CFG, "AbilitySound", false)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            area(c);
            long now = c.onClient(mc -> System.currentTimeMillis());
            chat(c, "You used your Maniac Miner Pickaxe Ability!", now - 20_000);
            chat(c, "Your Maniac Miner has expired!", now);
            long t = c.onClient(mc -> System.currentTimeMillis());
            chat(c, "You used your Maniac Miner Pickaxe Ability!", t);
            String[] names = {"full", "half", "low"};
            long[] waitMs = {600, 9_500, 8_500};
            for (int i = 0; i < 3; i++) {
                try { Thread.sleep(waitMs[i]); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                c.ctx().waitTicks(2);
                List<String> l = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(ARA, "timer")));
                c.check(l.size() == 1 && l.get(0).contains("Maniac Miner: Active "), "live HUD line " + names[i] + ": " + l);
                java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName("1117-ability-" + names[i]));
                dev.testkit.harness.Report.screenshot("1117-ability-shots", shot);
            }
            c.note("live screenshots full/half/low");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            hub(c);
        }
    }
}
