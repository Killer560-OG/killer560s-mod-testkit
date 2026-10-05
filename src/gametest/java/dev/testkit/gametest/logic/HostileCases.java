package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 353: lines another PLAYER can produce must not drive an action-gating pattern, and no pattern may hang or throw on
 * hostile input.
 *
 * <p>The action-gating list is the {@code logic/action-gating.json} fixtures (one genuine line per pattern, cited to
 * the mod source). For each: the genuine line must match (positive control - a sweep whose patterns match nothing
 * proves nothing), then forged variants must not:
 * <ul>
 *   <li>the genuine line typed by a stranger in all chat, party, guild, DM and co-op ({@code [VIP] Eve: <line>} ...).
 *       A match is only allowed when the fixture's {@code senderGroup} captures the forger ({@code Eve}) - then the
 *       line is correctly attributed to the player who typed it - or, for a channel-prefix pattern of a player-chat
 *       fixture, when the forgery really is on that channel;</li>
 *   <li>the name swapped for a 17-character one (Hypixel names are 3-16), for patterns that claim a bound;</li>
 *   <li>every number swapped for 20 digits: matching must not throw, and a fixture marked {@code parsedAsInt} must not
 *       capture a number Integer.parseInt cannot hold.</li>
 * </ul>
 * Then the handlers themselves are fed forged lines (Auto Door Opener, PartyTracker, PartyLeaderTracker), and every
 * catalogued pattern is run against long hostile strings under a deadline.
 */
final class HostileCases {

    static final String FORGER = "Eve";
    static final String LONG_NAME = "Abcdefghijklmnopq"; // 17
    static final String BIG = "99999999999999999999"; // 20 digits
    static final List<String> PREFIXES = List.of(
            "[VIP] Eve: ",
            "[312] [MVP+] Eve: ",
            "Eve: ",
            "Party > [MVP+] Eve: ",
            "Party > Eve: ",
            "Guild > Eve [Member]: ",
            "From [VIP] Eve: ",
            "Co-op > Eve: ");

    private HostileCases() {
    }

    /** The logic/ fixtures that drive the sweep. */
    static List<Fixtures.Fixture> gatingFixtures() {
        return Fixtures.load("logic");
    }

    /** Every action-gating pattern ref (matches + gates of the logic fixtures, minus hostileExempt). */
    static Set<String> actionGating() {
        Set<String> out = new LinkedHashSet<>();
        for (Fixtures.Fixture f : gatingFixtures()) {
            Set<String> exempt = exempt(f).keySet();
            for (String ref : refs(f)) {
                if (!exempt.contains(ref)) {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    static void sweep(LogicCase c) {
        List<Fixtures.Fixture> fixtures = gatingFixtures();
        c.check("action-gating fixtures loaded", fixtures.size() >= 15, fixtures.size() + "");
        int forgedTried = 0;
        List<String> lenient = new ArrayList<>();
        List<String> bigNumbers = new ArrayList<>();
        for (Fixtures.Fixture f : fixtures) {
            List<String> refs = refs(f);
            if (refs.isEmpty()) {
                continue;
            }
            String genuine = FixtureCases.texts(f).get(0);
            JsonObject j = f.json();
            String sender = j.has("sender") ? j.get("sender").getAsString() : null;
            int senderGroup = j.has("senderGroup") ? j.get("senderGroup").getAsInt() : -1;
            String channel = j.has("channel") ? j.get("channel").getAsString() : null;
            Map<String, String> exempt = exempt(f);
            boolean parsedAsInt = j.has("parsedAsInt") && j.get("parsedAsInt").getAsBoolean();
            for (String ref : refs) {
                Pattern p = R.pattern(ref);
                // Positive control.
                if (!c.check("genuine " + f.id() + " " + ref, FixtureCases.hit(p, genuine, f.mode()),
                        "\"" + genuine + "\" " + f.mode() + " /" + p.pattern() + "/")) {
                    continue;
                }
                boolean isExempt = exempt.containsKey(ref);
                // Forged prefixes.
                for (String prefix : PREFIXES) {
                    String forged = prefix + genuine;
                    forgedTried++;
                    Matcher m = p.matcher(forged);
                    boolean matched = "matches".equals(f.mode()) ? m.matches() : m.find();
                    if (!matched || isExempt) {
                        continue;
                    }
                    boolean attributed = senderGroup > 0 && senderGroup <= m.groupCount()
                            && FORGER.equals(m.group(senderGroup));
                    boolean sameChannel = senderGroup == 0 && channel != null && prefix.startsWith(channel);
                    c.check("forged " + f.id() + " " + ref, attributed || sameChannel,
                            "\"" + forged + "\" " + f.mode() + " /" + p.pattern() + "/"
                                    + (senderGroup > 0 && senderGroup <= m.groupCount()
                                    ? " captured sender \"" + m.group(senderGroup) + "\"" : ""));
                }
                // 17-character name.
                if (sender != null && genuine.contains(sender)) {
                    String longName = genuine.replace(sender, LONG_NAME);
                    boolean matched = FixtureCases.hit(p, longName, f.mode());
                    boolean bounded = p.pattern().contains(",16}");
                    if (bounded) {
                        c.check("17-char name " + f.id() + " " + ref, !matched, "\"" + longName + "\" matched");
                    } else if (matched) {
                        lenient.add(ref);
                    }
                }
                // 20-digit numbers.
                String unnamed = sender != null ? genuine.replace(sender, "") : genuine;
                if (unnamed.matches(".*\\d.*")) {
                    // Numbers only: the sender's name keeps its digits, or a bounded name group would fail first and
                    // hide what the number groups do.
                    String masked = sender != null ? genuine.replace(sender, "\u0001") : genuine;
                    String big = masked.replaceAll("\\d+", BIG);
                    big = sender != null ? big.replace("\u0001", sender) : big;
                    Matcher m = p.matcher(big);
                    boolean matched;
                    try {
                        matched = "matches".equals(f.mode()) ? m.matches() : m.find();
                    } catch (RuntimeException e) {
                        c.check("20-digit " + f.id() + " " + ref, false, "matching threw " + e);
                        continue;
                    }
                    if (matched) {
                        for (int g = 1; g <= m.groupCount(); g++) {
                            String v = m.group(g);
                            if (v != null && v.matches("\\d{10,}")) {
                                if (parsedAsInt) {
                                    c.check("20-digit " + f.id() + " " + ref, false,
                                            "captured " + v + " in group " + g + " of a fixture parsed with Integer.parseInt");
                                } else {
                                    bigNumbers.add(ref + " g" + g);
                                }
                            }
                        }
                    } else {
                        c.check("20-digit " + f.id() + " " + ref, true);
                    }
                }
            }
        }
        c.check("forgeries tried", forgedTried >= 100, forgedTried + "");
        c.note("forged lines tried: " + forgedTried + "; unbounded-name patterns accepting a 17-char name: "
                + new LinkedHashSet<>(lenient) + "; patterns capturing a 20-digit number (guarded parse or not parsed): "
                + new LinkedHashSet<>(bigNumbers));
        handlers(c);
        backtracking(c);
    }

    // ---- handler-level ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void handlers(LogicCase c) {
        // Auto Door Opener: a genuine key line arms it (positive control), a forged one must not.
        String door = "doorhelpers.AutoDoorOpenerFeature";
        Object savedTicks = R.get(door, "pendingTicks");
        Object savedType = R.get(door, "pendingType");
        try {
            R.set(door, "pendingTicks", 0);
            Mod.staticCall(door, "onChat", "Killer560 has obtained Wither Key!");
            int armed = ((Number) R.get(door, "pendingTicks")).intValue();
            c.check("door opener arms on the genuine key line (control)", armed > 0, "pendingTicks " + armed);
            for (String prefix : PREFIXES) {
                R.set(door, "pendingTicks", 0);
                String forged = prefix + "Killer560 has obtained Wither Key!";
                Mod.staticCall(door, "onChat", forged);
                int t = ((Number) R.get(door, "pendingTicks")).intValue();
                c.check("door opener ignores \"" + forged + "\"", t == 0, "armed for " + t + " ticks");
            }
        } finally {
            R.set(door, "pendingTicks", savedTicks);
            R.set(door, "pendingType", savedType);
        }

        // PartyTracker: the teammate list Party Commands authorises against.
        String tracker = "leapmenu.PartyTracker";
        Collection<String> members = (Collection<String>) R.get(tracker, "MEMBERS");
        List<String> saved = new ArrayList<>(members);
        try {
            members.clear();
            Mod.staticCall(tracker, "onChat", Component.literal("Killer560 joined the party."));
            c.check("PartyTracker adds on the genuine join line (control)", contains(members, "Killer560"),
                    "members " + members);
            members.clear();
            members.add("Killer560");
            Mod.staticCall(tracker, "onChat", Component.literal("Killer560 has disbanded the party!"));
            c.check("PartyTracker clears on the genuine disband line (control)", members.isEmpty(), "members " + members);
            String[] forgedLines = {
                "Mallory joined the party.",
                "You have joined Mallory's party!",
                "Party Members (1)",
                "Killer560 has disbanded the party!",
                "Killer560 has left the party.",
                "The party was transferred to Mallory because Killer560 left",
                "You left the party."};
            for (String line : forgedLines) {
                for (String prefix : List.of("[VIP] Eve: ", "[312] [MVP+] Eve: ", "Eve: ", "Guild > Eve [Member]: ")) {
                    members.clear();
                    members.add("Killer560");
                    String forged = prefix + line;
                    Mod.staticCall(tracker, "onChat", Component.literal(forged));
                    boolean unchanged = members.size() == 1 && contains(members, "Killer560");
                    c.check("PartyTracker ignores \"" + forged + "\"", unchanged, "members became " + members);
                }
            }
        } finally {
            members.clear();
            members.addAll(saved);
            R.set(tracker, "readingList", false);
        }

        // PartyLeaderTracker: the leader that gates leader-only party commands.
        String leaderCls = "partycommands.PartyLeaderTracker";
        Object savedLeader = R.get(leaderCls, "leader");
        try {
            R.set(leaderCls, "leader", null);
            Mod.staticCall(leaderCls, "onServerLine", "You have joined Killer560's party!");
            c.eq("leader from the genuine join line (control)", "Killer560", R.get(leaderCls, "leader"));
            String[] forgedLines = {
                "You have joined Mallory's party!",
                "The party was transferred to Mallory by Killer560",
                "Party Leader: Mallory ●",
                "Killer560 has promoted Mallory to Party Leader",
                "Killer560 has disbanded the party!",
                "You left the party."};
            for (String line : forgedLines) {
                for (String prefix : List.of("[VIP] Eve: ", "Eve: ", "Party > Eve: ")) {
                    R.set(leaderCls, "leader", "Killer560");
                    String forged = prefix + line;
                    Mod.staticCall(leaderCls, "onServerLine", forged);
                    c.eq("PartyLeaderTracker ignores \"" + forged + "\"", "Killer560", R.get(leaderCls, "leader"));
                }
            }
            // 20-digit / long junk must not throw.
            c.noThrow("PartyLeaderTracker on junk", () -> {
                Mod.staticCall(leaderCls, "onServerLine", "Party Leader: [" + BIG + "] " + LONG_NAME + " ●");
                return null;
            });
        } finally {
            R.set(leaderCls, "leader", savedLeader);
        }
    }

    private static boolean contains(Collection<String> names, String name) {
        return names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    // ---- catastrophic backtracking -----------------------------------------------------------------------------

    /** Every catalogued pattern against long hostile strings, each match bounded by a deadline. */
    private static void backtracking(LogicCase c) {
        Catalog cat = Catalog.load();
        List<String> inputs = List.of(
                "[".repeat(256),
                "[VIP] ".repeat(45),
                "a".repeat(300),
                "a: ".repeat(100),
                " ".repeat(300) + "!",
                "1/".repeat(150),
                "Party > ".repeat(37) + "x",
                "§".repeat(150) + "a".repeat(150),
                "9".repeat(300),
                "x ".repeat(150) + "❤");
        int runs = 0;
        long worstNs = 0;
        String worst = "";
        for (Catalog.Entry e : cat.entries) {
            String ref = e.ref();
            if (ref == null) {
                continue;
            }
            Pattern p;
            try {
                p = R.pattern(ref);
            } catch (AssertionError err) {
                continue; // 350 reports it
            }
            for (String in : inputs) {
                runs++;
                long t0 = System.nanoTime();
                try {
                    Matcher m = p.matcher(new Deadline(in, t0 + 250_000_000L));
                    m.find();
                    m.reset();
                    m.matches();
                } catch (Deadline.Expired x) {
                    c.check("bounded " + ref, false, "over 250 ms on a " + in.length() + "-char input starting \""
                            + in.substring(0, Math.min(12, in.length())) + "\"");
                    continue;
                } catch (StackOverflowError so) {
                    c.check("no stack overflow " + ref, false, "on a " + in.length() + "-char input");
                    continue;
                }
                long dt = System.nanoTime() - t0;
                if (dt > worstNs) {
                    worstNs = dt;
                    worst = ref;
                }
            }
        }
        c.check("backtracking runs", runs > 3000, runs + "");
        c.note(String.format(java.util.Locale.ROOT, "backtracking sweep: %d match runs, slowest %.2f ms (%s)", runs,
                worstNs / 1e6, worst));
    }

    /** A CharSequence that throws once a deadline passes, so a runaway regex cannot hang the client. */
    static final class Deadline implements CharSequence {
        static final class Expired extends RuntimeException {
            Expired() {
                super("regex deadline", null, false, false);
            }
        }

        private final String s;
        private final long deadline;
        private int calls;

        Deadline(String s, long deadlineNanos) {
            this.s = s;
            this.deadline = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0x3FF) == 0 && System.nanoTime() > deadline) {
                throw new Expired();
            }
            return s.charAt(index);
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(s.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return s;
        }
    }

    // ---- fixture extensions ---------------------------------------------------------------------------------

    static List<String> refs(Fixtures.Fixture f) {
        List<String> out = new ArrayList<>(f.matches());
        out.addAll(FixtureCases.gates(f));
        return out;
    }

    static Map<String, String> exempt(Fixtures.Fixture f) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (f.json().has("hostileExempt") && f.json().get("hostileExempt").isJsonObject()) {
            f.json().getAsJsonObject("hostileExempt").entrySet()
                    .forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return out;
    }
}
