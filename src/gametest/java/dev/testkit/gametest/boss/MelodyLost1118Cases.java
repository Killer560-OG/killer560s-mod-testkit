package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * melodylost-1118 (killer560: "time lost to Melody"): per P3 section, a Melody terminal that is the section's LAST objective
 * costs (its completion - the previous completion); nothing else counts. Every case runs real chat lines through the real
 * Floor7Tracker stages (F7 boss, P3 platform, HxMateA/HxMateB), with real waits between the lines. The mod's number is
 * compared with the wall time the test itself measured between the two lines. Chat is read back from the client's chat
 * window; nothing may reach the server.
 */
final class MelodyLost1118Cases {

    private static final String TT = "splittimers.TerminalTimersConfig";
    private static final String ML = "splittimers.MelodyLost";
    private static final String INTEL = "bridge.MelodyIntel";
    private static final String GATE = "The gate has been destroyed!";

    private MelodyLost1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-boss-melodylost-share", c -> last(c, "share", "HxMateB", "terminal", true, "team share"));
        s.test("1118-boss-melodylost-own", c -> last(c, "own", null, "terminal", true, "own menu"));
        s.test("1118-boss-melodylost-chat", c -> last(c, "chat", "HxMateA", "terminal", true, "party chat"));
        s.test("1118-boss-melodylost-chat-fraction", c -> last(c, "chat2", "HxMateA", "terminal", true, "party chat"));
        s.test("1118-boss-melodylost-no-evidence", c -> last(c, "none", "HxMateB", "terminal", false, "not a Melody"));
        s.test("1118-boss-melodylost-device-last", c -> last(c, "share", "HxMateB", "device", false, "device"));
        s.test("1118-boss-melodylost-lever-last", c -> last(c, "share", "HxMateB", "lever", false, "lever"));
        s.test("1118-boss-melodylost-earlier", MelodyLost1118Cases::earlier);
        s.test("1118-boss-melodylost-unknown", MelodyLost1118Cases::unknown);
        s.test("1118-boss-melodylost-no-carry", MelodyLost1118Cases::noCarry);
        s.test("1118-boss-melodylost-four-sections", MelodyLost1118Cases::fourSections);
        s.test("1118-boss-melodylost-off", MelodyLost1118Cases::off);
    }

    private static HxKit.Settings settings(Session c, boolean on) {
        return new HxKit.Settings(c).with(TT, "Enabled", true).with(TT, "MelodyLostSplits", on).with(TT, "MelodyLostHud", on)
                .with(TT, "MelodyLostPhase", on).with(TT, "MelodyLostTotal", on);
    }

    private static void line(Session c, String s) {
        c.hx().chat(s);
        c.ctx().waitTicks(3);
    }

    private static long lostMs(Session c, int sec) {
        return c.onClient(mc -> (Long) Mod.staticCall(ML, "lostMs", sec));
    }

    private static String decision(Session c, int sec) {
        return String.valueOf(c.onClient(mc -> Mod.staticCall(ML, "decision", sec)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Session c, String method) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(ML, method));
    }

    private static void evidence(Session c, String how, String who) {
        switch (how) {
            case "share" -> c.onClient(mc -> Mod.staticCall(INTEL, "offer", who, 5, 3));
            case "own" -> c.onClient(mc -> Mod.staticCall(INTEL, "offer", HxKit.self(c), 1, 2));
            case "chat" -> c.hx().chat("Party > [MVP+] " + who + ": Melody 67%");
            case "chat2" -> c.hx().chat("Party > " + who + ": Melody 1/3 - 33%");
            default -> { }
        }
        c.ctx().waitTicks(3);
    }

    /** Chat lines of the client's chat window added after {@code before}, newest last. */
    private static List<String> chatSince(Session c, int before) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
            List<String> out = new ArrayList<>();
            for (int i = all.size() - before - 1; i >= 0; i--) {
                out.add(all.get(i).content().getString());
            }
            return out;
        });
    }

    private static int chatCount(Session c) {
        return c.onClient(mc -> ((List<?>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages")).size());
    }

    private static void noServerChat(Session c) {
        for (String cmd : c.commands()) {
            c.check(!cmd.toLowerCase().contains("melody"), "a Melody line went to the server: " + cmd);
        }
    }

    /** Section 1 (three objectives): two quick ones, then a 1.5 s gap, then the last one of {@code kind} by {@code who}. */
    private static void last(Session c, String how, String who, String kind, boolean counts, String reason) throws Exception {
        try (HxKit.Settings st = settings(c, true)) {
            TeamMelodyRelayCases.inP3(c);
            String lastBy = who == null ? HxKit.self(c) : who;
            int before = chatCount(c);
            line(c, "HxMateA activated a terminal! (1/3)");
            line(c, "HxMateA activated a terminal! (2/3)");
            c.ctx().waitTicks(27);
            evidence(c, how, who == null ? HxKit.self(c) : who);
            long t0 = System.currentTimeMillis();
            String verb = kind.equals("terminal") ? "activated a terminal!" : kind.equals("device") ? "completed a device!" : "activated a lever!";
            // The previous completion is the 2/3 line sent above; measure from there.
            line(c, lastBy + " " + verb + " (3/3)");
            long lost = lostMs(c, 1);
            String why = decision(c, 1);
            c.note(how + " " + kind + " by " + lastBy + ": lost " + lost + " ms, " + why + " (waited ~" + (System.currentTimeMillis() - t0) + " ms after evidence)");
            if (counts) {
                c.check(lost >= 1200 && lost <= 2600, "expected about 1.5 s lost, the mod says " + lost);
                c.check(why.contains(reason), "decided by '" + why + "', wanted " + reason);
                List<String> chat = chatSince(c, before);
                c.check(chat.stream().anyMatch(t -> t.contains("Time lost to Melody (S1): ") && t.contains(lastBy)), "no per-phase chat line: " + chat);
                c.check(!list(c, "hudLines").isEmpty() && list(c, "hudLines").get(0).startsWith("S1: 1."), "HUD rows " + list(c, "hudLines"));
                c.check(list(c, "splitLines").stream().anyMatch(t -> t.contains("S1 Melody Lost")), "split rows " + list(c, "splitLines"));
            } else {
                c.check(lost == 0L, "nothing should be lost, the mod says " + lost);
                c.check(why.contains(reason), "decided by '" + why + "', wanted " + reason);
                c.check(chatSince(c, before).stream().noneMatch(t -> t.contains("Time lost to Melody")), "a loss line was printed");
                c.check(list(c, "splitLines").isEmpty(), "split rows " + list(c, "splitLines"));
            }
            noServerChat(c);
        }
    }

    /** Melody finished earlier in the section, someone else is last: nothing. */
    private static void earlier(Session c) throws Exception {
        try (HxKit.Settings st = settings(c, true)) {
            TeamMelodyRelayCases.inP3(c);
            line(c, "HxMateA activated a terminal! (1/3)");
            evidence(c, "share", "HxMateA");
            line(c, "HxMateA activated a terminal! (2/3)");
            c.ctx().waitTicks(27);
            line(c, "HxMateB activated a terminal! (3/3)");
            c.check(lostMs(c, 1) == 0L, "lost " + lostMs(c, 1));
            c.check(decision(c, 1).contains("not a Melody"), decision(c, 1));
        }
    }

    /** A lone 3/3 line with no 2/3 before it (joined late): unknown, 0. */
    private static void unknown(Session c) throws Exception {
        try (HxKit.Settings st = settings(c, true)) {
            TeamMelodyRelayCases.inP3(c);
            evidence(c, "share", "HxMateB");
            line(c, "HxMateB activated a terminal! (3/3)");
            c.check(lostMs(c, 1) == 0L, "lost " + lostMs(c, 1));
            c.check(decision(c, 1).contains("unknown"), decision(c, 1));
        }
    }

    /** Melody opened in S1 by B, S1 ends on someone else; B finishes S2 last with a normal terminal: S2 loses nothing. */
    private static void noCarry(Session c) throws Exception {
        try (HxKit.Settings st = settings(c, true)) {
            TeamMelodyRelayCases.inP3(c);
            line(c, "HxMateA activated a terminal! (1/3)");
            evidence(c, "share", "HxMateB");
            line(c, "HxMateA activated a terminal! (2/3)");
            line(c, "HxMateA activated a terminal! (3/3)");
            line(c, GATE);
            c.ctx().waitTicks(10);
            c.check("S2".equals(String.valueOf(Mod.staticCall("fastleap.Floor7Tracker", "getStage"))), "stage did not move to S2");
            line(c, "HxMateA activated a terminal! (1/3)");
            line(c, "HxMateA activated a terminal! (2/3)");
            c.ctx().waitTicks(27);
            line(c, "HxMateB activated a terminal! (3/3)");
            c.check(lostMs(c, 1) == 0L && lostMs(c, 2) == 0L, "S1 " + lostMs(c, 1) + " S2 " + lostMs(c, 2));
            c.check(decision(c, 2).contains("not a Melody"), decision(c, 2));
        }
    }

    /** Melody last in S1 and S3, normal in S2, Melody last in S4: the total is the sum and the total line is printed once. */
    private static void fourSections(Session c) throws Exception {
        try (HxKit.Settings st = settings(c, true)) {
            TeamMelodyRelayCases.inP3(c);
            int before = chatCount(c);
            boolean[] melody = {true, false, true, true};
            long sum = 0;
            for (int sec = 1; sec <= 4; sec++) {
                line(c, "HxMateA activated a terminal! (1/3)");
                line(c, "HxMateA activated a terminal! (2/3)");
                c.ctx().waitTicks(14);
                if (melody[sec - 1]) {
                    evidence(c, "share", "HxMateB");
                }
                line(c, "HxMateB activated a terminal! (3/3)");
                long lost = lostMs(c, sec);
                c.note("S" + sec + " lost " + lost + " ms (" + decision(c, sec) + ")");
                c.check(melody[sec - 1] ? lost >= 500 && lost <= 2000 : lost == 0L, "S" + sec + " lost " + lost);
                sum += lost;
                if (sec < 4) {
                    line(c, GATE);
                    c.ctx().waitTicks(10);
                }
            }
            List<String> chat = chatSince(c, before);
            long perPhase = chat.stream().filter(t -> t.contains("Time lost to Melody (S")).count();
            long totals = chat.stream().filter(t -> t.contains("Total time lost to Melody")).count();
            c.check(perPhase == 3, "per phase lines " + perPhase + ": " + chat);
            c.check(totals == 1, "total lines " + totals + ": " + chat);
            List<String> hud = list(c, "hudLines");
            c.check(hud.size() == 5 && hud.get(4).startsWith("Total: "), "HUD " + hud);
            String shown = String.format(java.util.Locale.US, "%.2fs", sum / 1000.0);
            c.check(hud.get(4).equals("Total: " + shown), "HUD total " + hud.get(4) + " vs " + shown);
            c.check(chat.stream().anyMatch(t -> t.contains("Total time lost to Melody: " + shown)), "total line lacks " + shown + ": " + chat);
            noServerChat(c);
            // A new run (Goldor's opening line again) starts from nothing.
            c.hx().chat("[BOSS] Goldor: Who dares trespass into my domain?");
            c.ctx().waitTicks(5);
            c.check(lostMs(c, 1) == 0L && list(c, "hudLines").isEmpty(), "not reset: " + list(c, "hudLines"));
        }
    }

    /** Defaults off: the same section as the first case records and prints nothing. */
    private static void off(Session c) throws Exception {
        try (HxKit.Settings st = settings(c, false)) {
            TeamMelodyRelayCases.inP3(c);
            int before = chatCount(c);
            line(c, "HxMateA activated a terminal! (1/3)");
            line(c, "HxMateA activated a terminal! (2/3)");
            evidence(c, "share", "HxMateB");
            c.ctx().waitTicks(20);
            line(c, "HxMateB activated a terminal! (3/3)");
            c.check(lostMs(c, 1) == 0L && list(c, "hudLines").isEmpty() && list(c, "splitLines").isEmpty(), "something tracked while off");
            c.check(chatSince(c, before).stream().noneMatch(t -> t.contains("Melody")), "a line was printed while off");
        }
    }
}
