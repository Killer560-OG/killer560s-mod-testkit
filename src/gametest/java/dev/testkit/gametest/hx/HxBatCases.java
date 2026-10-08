package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 521-523: the Murkbat bat bonus in the Score Calculator (mod branch map-info-bat, killer560 2026-10-07: "It needs a bat
 * checker ... the extra score from getting the first bat of a run by someone with the Murkbat attribute, just like how
 * Prince works").
 *
 * <p>The line is Hypixel's {@code A Bat has been slain. +1 Bonus Score}, the same string Odin ({@code Mimic.kt}) and
 * Skyblocker ({@code DungeonScore.java}) match; the Murkbat shard's Rekindle attribute is "+1 Bonus Score", "Limit of +1
 * Score per run", and the wiki's Dungeon Score page says it works out as one per PLAYER, up to 5 (which Odin counts
 * since 2026-10-05). The mod counts one per player: his own line, and a party mate's "Bat Killed!" for that mate.
 *
 * <ul>
 *   <li>521-hx-scorecalc-bat-line: forged copies (all chat, party chat, guild chat, extra text, another player's
 *       "Bat Killed!" outside party chat) count nothing; the real line, plain and coloured, counts once for him.</li>
 *   <li>522-hx-scorecalc-bat-score: the estimate's bonus rises by exactly 1 for the Prince line and by exactly 1 for the
 *       bat line, 1 more for a party mate's "Bat Killed!", nothing for his own party echo or a second line of his; and
 *       {@code ScoreCalculator.calculate} adds bat 0/1/3/9 as 0/1/3/5 (the cap), the old yes/no form as 1.</li>
 *   <li>523-hx-scorecalc-bat-reset: a new run (new world) clears the bat and the Prince, and the bonus returns.</li>
 * </ul>
 */
final class HxBatCases {

    private static final String SCF = "scorecalc.ScoreCalculatorFeature";
    private static final String CFG = "scorecalc.ScoreCalculatorConfig";
    static final String BAT_LINE = "A Bat has been slain. +1 Bonus Score";

    private HxBatCases() {
    }

    static void register(Session s) {
        s.test("521-hx-scorecalc-bat-line", HxBatCases::line);
        s.test("522-hx-scorecalc-bat-score", HxBatCases::score);
        s.test("523-hx-scorecalc-bat-reset", HxBatCases::reset);
    }

    private static Settings settings(Session c) {
        return new Settings(c).with(CFG, "Enabled", true)
                .with(CFG, "PrinceAlertEnabled", false)
                .with(CFG, "BatAlertEnabled", false);
    }

    /** A new run as the mod sees one: its run level forgotten, so the next tick starts a fresh run. */
    private static void newRun(Session c) {
        run(c, () -> Mod.setField(SCF, "runLevel", new WeakReference<>(null)));
        c.waitUntil("a new run tracked (bat and prince cleared)", mc -> !batKilled() && !princeKilled()
                && batBonus() == 0, 40);
    }

    private static boolean batKilled() {
        return (Boolean) Mod.staticCall(SCF, "isBatKilled");
    }

    private static boolean princeKilled() {
        return (Boolean) Mod.staticCall(SCF, "isPrinceKilled");
    }

    private static int batBonus() {
        return ((Number) Mod.staticCall(SCF, "batBonus")).intValue();
    }

    private static int bonus() {
        Object r = Mod.staticCall(SCF, "currentResult");
        return r == null ? -1 : ((Number) Mod.call(r, "bonus")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static String killers(Session c) {
        return c.onClient(mc -> String.valueOf(List.copyOf((Set<String>) Mod.field(SCF, "batKillers"))));
    }

    // ---- 521 ----------------------------------------------------------------------------------------------------

    private static void line(Session c) {
        try (Settings set = settings(c)) {
            dungeon(c);
            newRun(c);
            // The mod's one constant is the line Hypixel sends; this case sends that exact string.
            boolean same = c.onClient(mc -> Mod.pattern(SCF, "BAT_KILLED").matcher(BAT_LINE).matches());
            c.check(same, "ScoreCalculatorFeature.BAT_KILLED does not match \"" + BAT_LINE + "\"");
            String[] forged = {
                    "§7[VIP] HxEvil§f: " + BAT_LINE,
                    "§9Party §8> §b[MVP§c+§b] HxMateA§f: " + BAT_LINE,
                    "§2Guild > §7HxEvil§f: " + BAT_LINE,
                    BAT_LINE + " and another",
                    "HxEvil: " + BAT_LINE,
                    "§7[VIP] HxEvil§f: Bat Killed!",
                    "§2Guild > §7HxEvil§f: Bat Killed!",
                    "§dFrom §7HxEvil§f: Bat Killed!"};
            for (String f : forged) {
                c.hx().chat(f);
            }
            c.ctx().waitTicks(20);
            c.check(!c.onClient(mc -> batKilled()), "a forged line counted the bat: killers " + killers(c));
            c.check(c.onClient(mc -> batBonus()) == 0, "a forged line gave a bat bonus of " + c.onClient(mc -> batBonus()));
            assertConnected(c, "the forged bat lines");

            c.hx().chat(BAT_LINE);
            c.waitUntil("isBatKilled() after Hypixel's bat line", mc -> batKilled(), 40);
            String self = self(c).toLowerCase(Locale.ROOT);
            String k1 = killers(c);
            c.check(k1.equals("[" + self + "]"), "the bat was counted for " + k1 + ", want his own name " + self);
            c.check(c.onClient(mc -> batBonus()) == 1, "batBonus " + c.onClient(mc -> batBonus()) + " after one line");

            // A coloured copy of the same server line is the same bonus (stripped before matching), not a second one.
            c.hx().chat("§r§e" + BAT_LINE.replace("+1", "§a+1"));
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> batBonus()) == 1, "a second line of his own counted twice: " + killers(c));
            c.note("8 forged lines counted nothing; the real line -> killers " + k1 + ", batBonus 1; a coloured repeat"
                    + " stays 1");
        }
    }

    // ---- 522 ----------------------------------------------------------------------------------------------------

    private static void score(Session c) {
        try (Settings set = settings(c)) {
            dungeon(c, "secrets", 20, "secretsPercent", "40.0", "crypts", 2, "rooms", 10, "deaths", 0);
            newRun(c);
            c.waitUntil("a score estimate with crypts 2", mc -> bonus() >= 2
                    && ((Number) Mod.staticCall(SCF, "getCrypts")).intValue() == 2, 100);
            c.ctx().waitTicks(12);
            int b0 = c.onClient(mc -> bonus());
            send(c, "chat.prince-killed");
            c.waitUntil("bonus " + (b0 + 1) + " after the Prince line", mc -> bonus() == b0 + 1, 60);
            c.hx().chat(BAT_LINE);
            c.waitUntil("bonus " + (b0 + 2) + " after the bat line (+1, like the Prince)", mc -> bonus() == b0 + 2, 60);
            // A party mate's call: his bat, one more point.
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: Bat Killed!");
            c.waitUntil("bonus " + (b0 + 3) + " after HxMateA's \"Bat Killed!\"", mc -> bonus() == b0 + 3, 60);
            // His own echo (the alert he sends comes back in party chat) and the same mate again: nothing more.
            String self = self(c);
            c.hx().chat("§9Party §8> §a[VIP] " + self + "§f: Bat Killed!");
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: bat dead!");
            c.ctx().waitTicks(25);
            int after = c.onClient(mc -> bonus());
            c.check(after == b0 + 3, "his own echo or a repeat from HxMateA changed the bonus to " + after + " (want "
                    + (b0 + 3) + "); killers " + killers(c));
            int bb = c.onClient(mc -> batBonus());
            c.check(bb == 2, "batBonus " + bb + ", want 2 (him and HxMateA)");

            // The formula itself: the bat's points against the Prince's, and the cap.
            int[] want = {0, 1, 3, 5};
            int[] bats = {0, 1, 3, 9};
            int capped = c.onClient(mc -> ((Number) Mod.field("scorecalc.ScoreCalculator", "BAT_BONUS_CAP")).intValue());
            StringBuilder got = new StringBuilder();
            int base = c.onClient(mc -> calcBonus(false, 0));
            for (int i = 0; i < bats.length; i++) {
                int n = bats[i];
                int d = c.onClient(mc -> calcBonus(false, n)) - base;
                got.append(" bat ").append(n).append("->+").append(d);
                c.check(d == want[i], "calculate() with batBonus " + n + " added " + d + ", want " + want[i]);
            }
            int prince = c.onClient(mc -> calcBonus(true, 0)) - base;
            int oldShape = c.onClient(mc -> calcBonusOld(true)) - base;
            c.check(prince == 1, "the Prince added " + prince);
            c.check(oldShape == 1, "the old yes/no Inputs constructor with a bat added " + oldShape);
            c.note("estimate bonus " + b0 + " -> prince " + (b0 + 1) + " -> his bat " + (b0 + 2) + " -> HxMateA's "
                    + (b0 + 3) + "; echo/repeat stay " + after + "; calculate():" + got + ", prince +" + prince
                    + ", old boolean form +" + oldShape + ", BAT_BONUS_CAP " + capped);
        }
    }

    /** {@code ScoreCalculator.calculate(...).bonus()} for an otherwise empty F7 run (no crypts, no Paul). */
    private static int calcBonus(boolean prince, int bat) {
        Object in = construct(new Object[]{"F7", 50.0, 20, 0, 10, 40, 0, 3, 1, 0, 300, false, false, false, prince,
                bat, false, false});
        return ((Number) Mod.call(Mod.staticCall("scorecalc.ScoreCalculator", "calculate", in), "bonus")).intValue();
    }

    private static int calcBonusOld(boolean bat) {
        Object in = construct(new Object[]{"F7", 50.0, 20, 0, 10, 40, 0, 3, 1, 0, 300, false, false, false, false,
                bat, false, false});
        return ((Number) Mod.call(Mod.staticCall("scorecalc.ScoreCalculator", "calculate", in), "bonus")).intValue();
    }

    /** The Inputs constructor whose bat parameter has the type of {@code args[15]} (int or boolean). */
    private static Object construct(Object[] args) {
        Class<?> want = args[15] instanceof Boolean ? boolean.class : int.class;
        for (Constructor<?> k : Mod.cls("scorecalc.ScoreCalculator$Inputs").getDeclaredConstructors()) {
            if (k.getParameterCount() == args.length && k.getParameterTypes()[15] == want) {
                try {
                    k.setAccessible(true);
                    return k.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("new Inputs threw " + e, e);
                }
            }
        }
        throw new AssertionError("no ScoreCalculator.Inputs constructor takes a " + want + " bat");
    }

    // ---- 523 ----------------------------------------------------------------------------------------------------

    private static void reset(Session c) {
        try (Settings set = settings(c)) {
            dungeon(c, "secrets", 20, "secretsPercent", "40.0", "crypts", 2, "rooms", 10, "deaths", 0);
            newRun(c);
            c.waitUntil("a score estimate", mc -> bonus() >= 0, 100);
            c.ctx().waitTicks(12);
            int b0 = c.onClient(mc -> bonus());
            send(c, "chat.prince-killed");
            c.hx().chat(BAT_LINE);
            c.hx().chat("§9Party §8> §7HxMateB§f: Bat Killed!");
            c.waitUntil("bonus " + (b0 + 3) + " with the Prince and two bats", mc -> bonus() == b0 + 3, 60);
            c.check(c.onClient(mc -> batBonus()) == 2, "batBonus before the reset " + c.onClient(mc -> batBonus()));
            String before = killers(c);
            // The next run: Hypixel puts every run in a new world, which is what the mod resets on.
            run(c, () -> Mod.setField(SCF, "runLevel", new WeakReference<>(null)));
            c.waitUntil("the new run cleared the bat and the Prince",
                    mc -> !batKilled() && !princeKilled() && batBonus() == 0, 40);
            c.waitUntil("the estimate's bonus back to " + b0, mc -> bonus() == b0, 60);
            String afterK = killers(c);
            c.check(afterK.equals("[]"), "bat killers survived the new run: " + afterK);
            // And the new run counts a bat of its own again (the latch is not stuck).
            c.hx().chat(BAT_LINE);
            c.waitUntil("the new run counts a new bat line", mc -> batBonus() == 1, 40);
            c.note("before: bonus " + (b0 + 3) + ", killers " + before + "; new run: killers " + afterK + ", bonus "
                    + b0 + "; a bat in the new run counts again");
        }
    }
}
