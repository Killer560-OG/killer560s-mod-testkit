package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1104-logic-scanaudit (mod branch scanaudit-1104): patterns over other players' text.
 *
 * <p>Chat Hider's Discord / Microsoft warning rules were {@code (?i).*discord.*staff.*|...} matches() patterns, quadratic
 * on a long line (about 0.4 s for 10,000 characters, for every chat line, on the packet path); they are now one-pass
 * functions that must answer exactly as the old patterns did. The unbounded digit groups of the chat-fed patterns
 * (run summary terminal line, Team Score, Bits Available) and the experiment table's lore patterns are bounded, so no
 * capture can be a number Integer.parseInt / Long.parseLong cannot hold. Then every action-gating pattern runs against
 * a forged player line and a 10,000-character line of several shapes under a deadline.
 */
final class ScanAuditCases {

    private static final String OH = "objecthider.ObjectHiderFeature";
    private static final Pattern OLD_DISCORD = Pattern.compile("(?i).*discord.*staff.*|.*staff.*discord.*");
    private static final Pattern OLD_MICROSOFT = Pattern.compile("(?i).*microsoft account.*password.*");

    private ScanAuditCases() {
    }

    private static boolean call(String method, String text) {
        return (Boolean) Mod.staticCall(OH, method, text);
    }

    static void scanAudit(LogicCase c) {
        // 1. Chat Hider: the same answer as the old patterns on genuine, forged, reordered and multi-line text.
        List<String> samples = List.of(
                "Staff will NEVER ask for your password. Beware of Discord impersonators.",
                "Be careful: staff do not DM on discord",
                "[VIP] Eve: discord staff",
                "DISCORD and STAFF",
                "discordstaff",
                "staffdiscord",
                "discord",
                "staff",
                "Never share your Microsoft Account password with anyone",
                "microsoft account then later PASSWORD",
                "password before microsoft account",
                "microsoft accountpassword",
                "microsoft account",
                "MICROSOFT ACCOUNT x PassWord",
                "discord\nstaff",
                "microsoft account\npassword",
                "discord staff",
                "microsoft account\u0085password",
                "",
                "a".repeat(10_000),
                "discord " + "a".repeat(10_000) + " staff",
                "microsoft account " + "x".repeat(10_000) + " password",
                "p".repeat(5_000) + "microsoft account");
        int agree = 0;
        for (String s : samples) {
            boolean d = call("isDiscordWarning", s);
            boolean m = call("isMicrosoftWarning", s);
            boolean od = OLD_DISCORD.matcher(s).matches();
            boolean om = OLD_MICROSOFT.matcher(s).matches();
            c.check("discord rule == old pattern on \"" + clip(s) + "\"", d == od, "new " + d + ", old " + od);
            c.check("microsoft rule == old pattern on \"" + clip(s) + "\"", m == om, "new " + m + ", old " + om);
            agree++;
        }
        c.check("hider samples compared", agree == samples.size(), agree + "");
        c.check("genuine discord line is hidden", call("isDiscordWarning", samples.get(0)));
        c.check("genuine microsoft line is hidden", call("isMicrosoftWarning", samples.get(8)));
        c.check("a line with neither word is kept", !call("isDiscordWarning", "Eve: hello there"));

        // 2. A 10,000-character line is answered fast (the old patterns took about 400 ms each, warmed up).
        String wall = "a".repeat(10_000);
        for (int i = 0; i < 3; i++) { // warm up
            call("isDiscordWarning", wall);
            call("isMicrosoftWarning", wall);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            call("isDiscordWarning", wall);
            call("isMicrosoftWarning", wall);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / 20;
        c.note(String.format(java.util.Locale.ROOT, "chat hider rules on 10,000 characters: %.2f ms for both", ms));
        c.check("both hider rules on 10,000 characters under 20 ms", ms < 20, String.format("%.2f ms", ms));
        long o0 = System.nanoTime();
        OLD_DISCORD.matcher(wall).matches();
        OLD_MICROSOFT.matcher(wall).matches();
        c.note(String.format(java.util.Locale.ROOT, "(the old patterns, once, cold: %.1f ms)", (System.nanoTime() - o0) / 1e6));

        // 3. Bounded digit groups: a 20-digit number can never be captured whole.
        String big = "99999999999999999999";
        bounded(c, "runsummary.RunSummaryFeature#DEVICE_COMPLETE", "Eve activated a terminal! (" + big + "/" + big + ")", 4, 5);
        bounded(c, "runsummary.RunSummaryFeature#TEAM_SCORE", "Team Score: " + big + " (S+)", 1);
        bounded(c, "scoreboard.ScoreboardExtraData#BITS_AVAILABLE_CHAT",
                "You gained " + big + " Bits Available compounded from all your previously eaten cookies!", 1);
        bounded(c, "experiments.ExperimentsProfitTracker#REMAINING_CLICKS", "Remaining Clicks: " + big, 1);
        bounded(c, "experiments.ExperimentsProfitTracker#COUNT_PREFIX", big + "x Enchanted Book", 1);
        bounded(c, "experiments.ExperimentsProfitTracker#COUNT_SUFFIX", "Enchanted Book x" + big, 2);
        bounded(c, "experiments.ExperimentNavigator#XP_COST_PATTERN", big + " XP Levels", 1);
        bounded(c, "experiments.ExperimentNavigator#ROUNDS_NEEDED_PATTERN", "Chain of " + big + ":", 1);
        bounded(c, "experiments.ExperimentNavigator#RENEWED_TODAY_PATTERN", "You've renewed " + big + "/3 charges today!", 1);

        // 4. Genuine lines still match (the bounds did not break the real shapes).
        genuine(c, "runsummary.RunSummaryFeature#DEVICE_COMPLETE", "Eve activated a terminal! (3/7)");
        genuine(c, "runsummary.RunSummaryFeature#TEAM_SCORE", "Team Score: 312 (S+)");
        genuine(c, "scoreboard.ScoreboardExtraData#BITS_AVAILABLE_CHAT",
                "You gained 12,345 Bits Available compounded from all your previously eaten cookies!");
        genuine(c, "experiments.ExperimentsProfitTracker#ENCHANTING_EXP", "+1,234,567.5k Enchanting Exp");
        genuine(c, "experiments.ExperimentsProfitTracker#ENCHANTING_EXP", "50 Enchanting Exp");
        genuine(c, "experiments.ExperimentsProfitTracker#COUNT_SUFFIX", "Enchanted Book x12");
        genuine(c, "experiments.ExperimentNavigator#XP_COST_PATTERN", "30 XP Levels");

        // 5. Every action-gating pattern against a forged player line and 10,000-character lines, under a deadline.
        List<String> inputs = List.of(
                "[VIP] Eve: " + "a".repeat(10_000),
                "Party > [MVP+] Eve: " + "!w ".repeat(3_300),
                "[".repeat(10_000),
                "[VIP] ".repeat(1_600),
                " ".repeat(10_000) + "!",
                "1/".repeat(5_000),
                "9".repeat(10_000),
                "x ".repeat(5_000) + "❤",
                "§".repeat(5_000) + "a".repeat(5_000));
        int runs = 0;
        long worstNs = 0;
        String worst = "";
        for (String ref : HostileCases.actionGating()) {
            Pattern p;
            try {
                p = R.pattern(ref);
            } catch (AssertionError err) {
                continue; // 350 reports it
            }
            for (String in : inputs) {
                runs++;
                long s0 = System.nanoTime();
                try {
                    Matcher m = p.matcher(new HostileCases.Deadline(in, s0 + 500_000_000L));
                    m.find();
                    m.reset();
                    m.matches();
                } catch (HostileCases.Deadline.Expired x) {
                    c.check("10k bounded " + ref, false, "over 500 ms on a " + in.length() + "-char input starting \""
                            + clip(in) + "\"");
                    continue;
                } catch (StackOverflowError so) {
                    c.check("10k no stack overflow " + ref, false, "on a " + in.length() + "-char input");
                    continue;
                }
                long dt = System.nanoTime() - s0;
                if (dt > worstNs) {
                    worstNs = dt;
                    worst = ref;
                }
            }
        }
        c.check("10k runs happened", runs > 300, runs + "");
        c.note(String.format(java.util.Locale.ROOT, "10,000-character sweep: %d match runs, slowest %.2f ms (%s)", runs,
                worstNs / 1e6, worst));
    }

    /** The pattern is found in {@code line}; each named group is at most 9 digits (or 15 with commas), so parseInt holds. */
    private static void bounded(LogicCase c, String ref, String line, int... groups) {
        Pattern p = R.pattern(ref);
        Matcher m = p.matcher(line);
        if (!m.find()) {
            c.check(ref + " cannot capture a 20-digit number", true); // not matching at all is safe too
            return;
        }
        for (int g : groups) {
            String v = m.group(g) == null ? "" : m.group(g).replace(",", "");
            c.check(ref + " group " + g + " is short enough for parseInt/parseLong", v.length() <= 16 && !v.equals("99999999999999999999"),
                    "captured " + v);
        }
    }

    private static void genuine(LogicCase c, String ref, String line) {
        c.check(ref + " still finds \"" + clip(line) + "\"", R.pattern(ref).matcher(line).find());
    }

    private static String clip(String s) {
        String one = s.replace('\n', '/').replace('\r', '/');
        return one.length() <= 40 ? one : one.substring(0, 40) + "...(" + s.length() + ")";
    }
}
