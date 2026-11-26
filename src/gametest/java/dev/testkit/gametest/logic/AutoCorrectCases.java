package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 1116-logic-autocorrect-exhaustive: every single-error typo of every known command and /warp destination. */
final class AutoCorrectCases {

    private AutoCorrectCases() {
    }

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz_.";

    @SuppressWarnings("unchecked")
    static void exhaustive(LogicCase c) throws Exception {
        Class<?> fixer = Mod.cls("autocorrect.CommandFixer");
        Method classify = fixer.getMethod("classify", String.class, Set.class);
        Method fix = fixer.getMethod("fix", String.class, Set.class);
        Method dist = fixer.getDeclaredMethod("distance", String.class, String.class);
        dist.setAccessible(true);
        Set<String> commands = new LinkedHashSet<>((Set<String>) fixer.getMethod("baseCommands").invoke(null));
        Field by = Mod.cls("gui.SearchCommands").getDeclaredField("BY_COMMAND");
        by.setAccessible(true);
        commands.addAll(((Map<String, ?>) by.get(null)).keySet());
        commands.addAll(List.of("kill", "tp", "gamemode", "give", "time", "weather", "help", "list", "me", "say"));
        Set<String> warps = (Set<String>) fixer.getMethod("warpDestinations").invoke(null);

        long[] total = new long[1];
        for (Object[] group : new Object[][] {{"command", commands}, {"warp destination", warps}}) {
            String kind = (String) group[0];
            Set<String> valid = (Set<String>) group[1];
            int generated = 0, corrected = 0, isValid = 0, ambiguous = 0, shortLeft = 0, wrong = 0, missed = 0, claimed = 0;
            TreeSet<String> ambiguousList = new TreeSet<>();
            List<String> wrongList = new ArrayList<>();
            List<String> claimedList = new ArrayList<>();
            List<String> missedList = new ArrayList<>();
            for (String target : valid) {
                if (target.length() < 2) {
                    continue; // one-letter commands (p, l, w, r, g, f) have no safe typo
                }
                Set<String> typos = new LinkedHashSet<>();
                for (int i = 0; i < target.length(); i++) {
                    typos.add(target.substring(0, i) + target.substring(i + 1));
                    for (char ch : ALPHABET.toCharArray()) {
                        typos.add(target.substring(0, i) + ch + target.substring(i + 1));
                    }
                    if (i + 1 < target.length()) {
                        typos.add(target.substring(0, i) + target.charAt(i + 1) + target.charAt(i) + target.substring(i + 2));
                    }
                }
                for (int i = 0; i <= target.length(); i++) {
                    for (char ch : ALPHABET.toCharArray()) {
                        typos.add(target.substring(0, i) + ch + target.substring(i));
                    }
                }
                typos.remove(target);
                for (String t : typos) {
                    if (t.isEmpty()) {
                        continue;
                    }
                    generated++;
                    String r = (String) classify.invoke(null, t, valid);
                    if (r.equals("VALID")) {
                        isValid++;
                    } else if (r.startsWith("FIXED:")) {
                        String got = r.substring(6);
                        if (got.equals(target)) {
                            corrected++;
                        } else if (!classify.invoke(null, t, Set.of(target)).toString().startsWith("FIXED:")
                                || (int) dist.invoke(null, t, got) <= (int) dist.invoke(null, t, target)) {
                            // the typo is just as much (or only) a typo of another command: not a wrong guess
                            claimed++;
                            claimedList.add(t + " (typo of " + target + ") -> " + got);
                        } else {
                            wrong++;
                            wrongList.add(t + " (typo of " + target + ") -> " + r);
                        }
                    } else if (r.startsWith("AMBIGUOUS:")) {
                        ambiguous++;
                        ambiguousList.add(t + " -> " + r.substring(10));
                    } else if (t.length() <= 3) {
                        shortLeft++;
                    } else {
                        missed++;
                        missedList.add(t + " (typo of " + target + ")");
                    }
                }
            }
            total[0] += generated;
            c.note(kind + " typos: generated " + generated + ", corrected " + corrected + ", left alone as already valid "
                    + isValid + ", left alone as ambiguous " + ambiguous + ", left alone as 3 letters or fewer (only swaps and"
                    + " doubled letters are fixed) " + shortLeft + ", also a typo of another command, fixed to that one " + claimed + ", wrong target " + wrong + ", missed " + missed);
            c.note(kind + " ambiguous: " + ambiguousList);
            c.note(kind + " fixed to another command (first 40): " + claimedList.subList(0, Math.min(40, claimedList.size())));
            c.check(kind + ": no typo is corrected to the wrong target", wrong == 0, wrongList.toString());
            c.check(kind + ": no typo of 4+ letters is missed", missed == 0, missedList.toString());
            c.check(kind + ": the generator acted (typos made, many corrected)", generated > 500 && corrected > generated / 4,
                    generated + " typos, " + corrected + " corrected");
        }

        // whole-line behavior through fix(): both entry points end here
        Set<String> known = commands;
        String[][] lines = {
                {"zb", "bz"}, {"ha", "ah"}, {"BZ", "bz"}, {"//bz", "bz"}, {"warpo base", "warp base"}, {"warp bsae", "warp base"},
                {"warpbase", "warp base"}, {"wrap bsae", "warp base"}, {"warp hbu", "warp hub"}, {"warp BASE", "warp base"},
                {"warp hub", "warp hub"}, {"warp hub now", "warp hub now"}, {"warp xqzv", "warp xqzv"}, {"msg bsae hi", "msg bsae hi"},
                {"pc", "pc"}, {"xq", "xq"}, {"p Steve", "p Steve"}, {"wardorbe", "wardrobe"}, {"wapr", "warp"}, {"warrp", "warp"},
                {"hubb", "hub"}, {"bzz", "bz"}, {"ahh", "ah"}, {"k5:x", "k5:x"},
                {"warp d h", "warp dh"}, {"warp dungeon hub", "warp dungeon_hub"}, {"warp ba se", "warp base"},
                {"warp dh", "warp dh"}, {"msg name d h", "msg name d h"}, {"warp xq zv", "warp xq zv"},
                {"warp hub now", "warp hub now"}, {"warp d h ", "warp d h "}, {"p d h", "p d h"}};
        for (String[] l : lines) {
            String got = (String) fix.invoke(null, l[0], known);
            c.check("/" + l[0] + " goes out as /" + l[1], got.equals(l[1]), "got /" + got);
        }
        Method parse = Mod.cls("autocorrect.AutoCorrectFeature").getMethod("unknownCommandLine", String.class);
        c.check("the reply line is read with its arguments", "warpo base".equals(parse.invoke(null,
                "Unknown command. Type \"/help\" for help. ('warpo base')")), "reply parse");
        c.check("another line is not a reply", parse.invoke(null, "Unknown command. Try again ('zb')") == null, "other text");
        c.check("a typo plus the reply's quoted line fixes the same way", "warp base".equals(fix.invoke(null,
                parse.invoke(null, "Unknown command. Type \"/help\" for help. ('warpo base')"), known)), "reply -> fix");
        c.note("total typos generated: " + total[0]);
    }

    @SuppressWarnings("unchecked")
    static void shortExtraLetter(LogicCase c) throws Exception {
        Method fix = Mod.cls("autocorrect.CommandFixer").getMethod("fix", String.class, Set.class);
        Set<String> et = Set.of("et", "wardrobe", "warp", "pet");
        Set<String> both = Set.of("et", "ty", "wardrobe");
        String[][] lines = {{"ety", "et"}, {"ety arg1", "et arg1"}, {"ETY", "et"}, {"wardorbe", "wardrobe"}, {"pet", "pet"},
                {"et", "et"}, {"warpp", "warp"}, {"xyz", "xyz"}};
        for (String[] l : lines) {
            String got = (String) fix.invoke(null, l[0], et);
            c.check("/" + l[0] + " goes out as /" + l[1], got.equals(l[1]), "got /" + got);
        }
        String amb = (String) fix.invoke(null, "ety", both);
        c.check("/ety with both et and ty known stays as typed", amb.equals("ety"), "got /" + amb);
        c.check("ety with no et known stays as typed", fix.invoke(null, "ety", Set.of("wardrobe")).equals("ety"), "none");
    }

    /** 1121-logic-warpdest: a misspelled /warp destination is completed only when exactly one Fast Travel name fits. */
    @SuppressWarnings("unchecked")
    static void warpDestination(LogicCase c) throws Exception {
        Class<?> fixer = Mod.cls("autocorrect.CommandFixer");
        Method fix = fixer.getMethod("fix", String.class, Set.class);
        Set<String> known = Set.of("warp", "visit", "msg", "p", "hub");
        Set<String> warps = (Set<String>) fixer.getMethod("warpDestinations").invoke(null);
        c.check("the destination table is loaded", warps.size() > 80 && warps.contains("base") && warps.contains("crypts"), "size " + warps.size());
        String[][] lines = {
                {"warp bas", "warp base"}, {"wrap bas", "warp base"}, {"warp crys", "warp crystals"}, {"warp base", "warp base"},
                {"warp village", "warp village"}, {"warp tunnel", "warp tunnel"}, {"warp tun", "warp tun"}, {"warp ba", "warp ba"},
                {"warp xqz", "warp xqz"}, {"warp bas now", "warp base now"}, {"warp bas  x", "warp bas  x"},
                {"visit bas", "visit bas"}, {"msg bas hi", "msg bas hi"}, {"warp hub", "warp hub"}, {"warp BAS", "warp base"}};
        for (String[] l : lines) {
            String got = (String) fix.invoke(null, l[0], known);
            c.check("/" + l[0] + " goes out as /" + l[1], got.equals(l[1]), "got /" + got);
        }
        Class<?> cfgClass = Mod.cls("autocorrect.AutoCorrectConfig");
        Object cfg = cfgClass.getMethod("getInstance").invoke(null);
        boolean was = (boolean) cfgClass.getMethod("isCorrectCommands").invoke(cfg);
        try {
            cfgClass.getMethod("setCorrectCommands", boolean.class).invoke(cfg, false);
            Object off = Mod.cls("autocorrect.AutoCorrectFeature").getMethod("correctOutgoingCommand", String.class).invoke(null, "warp bas");
            c.check("Correct Commands off sends /warp bas as typed", "warp bas".equals(off), "got " + off);
        } finally {
            cfgClass.getMethod("setCorrectCommands", boolean.class).invoke(cfg, was);
        }
    }

    /** 1125-logic-cmdtypo-firstletter: /ax (Hypixel: "Unknown command ('ax')") goes out as /sax; a dropped first letter is fixed only when one name fits. */
    @SuppressWarnings("unchecked")
    static void droppedFirstLetter(LogicCase c) throws Exception {
        Class<?> fixer = Mod.cls("autocorrect.CommandFixer");
        Method fix = fixer.getMethod("fix", String.class, Set.class);
        Set<String> base = (Set<String>) fixer.getMethod("baseCommands").invoke(null);
        c.check("sax is a known command", base.contains("sax"), "base list");
        Set<String> known = new LinkedHashSet<>(base);
        known.addAll(List.of("kill", "tp", "gamemode", "give", "time", "weather", "help", "list", "me", "say"));
        Method parse = Mod.cls("autocorrect.AutoCorrectFeature").getMethod("unknownCommandLine", String.class);
        Object line = parse.invoke(null, "Unknown command. Type \"/help\" for help. ('ax')");
        c.check("the reply quotes ax", "ax".equals(line), "got " + line);
        c.check("the reply's line ax is sent again as sax", "sax".equals(fix.invoke(null, line, known)), "got " + fix.invoke(null, line, known));
        String[][] lines = {{"ax", "sax"}, {"AX", "sax"}, {"ax x", "sax x"}, {"sax", "sax"}, {"ah", "ah"}, {"xq", "xq"}};
        for (String[] l : lines) {
            String got = (String) fix.invoke(null, l[0], known);
            c.check("/" + l[0] + " goes out as /" + l[1], got.equals(l[1]), "got /" + got);
        }
        c.check("ax with two names ending in ax stays as typed", fix.invoke(null, "ax", Set.of("sax", "tax", "warp")).equals("ax"), "ambiguous");
        c.check("ax with no matching name stays as typed", fix.invoke(null, "ax", Set.of("warp", "ah")).equals("ax"), "none");
    }
}
