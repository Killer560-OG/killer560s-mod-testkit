package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import java.util.List;
import static dev.testkit.gametest.hx.HxKit.*;

/**
 * partystate-1118 (killer560, 2026-11-18: "for everything relating to party it keeps missing times that i leave parties or
 * join parties ... they only update if i run /party list"). The one party tracker (leapmenu/PartyTracker) now owns who is
 * in the party, who leads it and whether there is one, for every feature that asked (Party Commands' leader gate, the
 * Custom Scoreboard crown, Spotify lyrics' chat gate), and reads SkyBlock's tab Party widget for what chat never says
 * (a Party Finder group joined with members already in it names only you; a restart or reconnect keeps the party).
 * <ul>
 *   <li>1118-hx-party-lines: every server line form found in his logs (main and alpha wording, with and without rank
 *       tags, the alpha's literal section-sign party chat), replayed in order, members / leader / in-party asserted after
 *       each line; a forged chat line changes nothing.</li>
 *   <li>1118-hx-party-widget: the tab widget supplies the members (his 11-16 Party Finder miss), says no party, is
 *       ignored while a chat line is fresher than 8 s, and a half-listed widget is ignored.</li>
 *   <li>1118-hx-party-readers: the leap order (Auto Leap) follows a join and a leave that only chat announced.</li>
 * </ul>
 */
final class HxPartyState1118Cases {
    private HxPartyState1118Cases() { }

    private static final String PT = "leapmenu.PartyTracker";

    static void register(Session s) {
        s.test("1118-hx-party-lines", HxPartyState1118Cases::lines);
        s.test("1118-hx-party-widget", HxPartyState1118Cases::widget);
        s.test("1118-hx-party-readers", HxPartyState1118Cases::readers);
    }

    /** "teammates|leader|inParty" as the tracker reports it now. */
    private static String state(Session c) {
        return c.onClient(mc -> stateNow());
    }

    private static String stateNow() {
        return Mod.staticCall(PT, "teammates") + "|" + Mod.staticCall(PT, "leader") + "|" + Mod.staticCall(PT, "inParty");
    }

    private static void say(Session c, String line) {
        c.hx().chat(line);
        c.ctx().waitTicks(8);
    }

    private static void step(Session c, String line, String expect) {
        say(c, line);
        String got = state(c);
        c.check(expect.equals(got), "after \"" + line + "\" the tracker reads " + got + ", wanted " + expect);
    }

    private static void lines(Session c) {
        hub(c);
        String me = self(c);
        say(c, "You are not in a party right now.");
        step(c, "You are not in a party right now.", "[]|null|false");
        step(c, "[MVP+] " + me + " invited [VIP] TerminallyIllCow to the party! They have 60 seconds to accept.", "[]|" + me + "|true");
        step(c, "[VIP] TerminallyIllCow joined the party.", "[TerminallyIllCow]|" + me + "|true");
        step(c, "[VIP] TerminallyIllCow has left the party.", "[]|" + me + "|true");
        step(c, "The party was disbanded because all invites expired and the party was empty.", "[]|null|false");
        step(c, "You have joined [VIP] agrophy's party!", "[agrophy]|agrophy|true");
        step(c, "You'll be partying with: [VIP] z5x4, [MVP+] MommyDegradeMe", "[agrophy, z5x4, MommyDegradeMe]|agrophy|true");
        step(c, "[MVP+] MommyDegradeMe has been removed from the party.", "[agrophy, z5x4]|agrophy|true");
        step(c, "[VIP] z5x4 was removed from your party because they disconnected.", "[agrophy]|agrophy|true");
        step(c, "The party was transferred to [MVP+] catgirlzeeks by [MVP+] agrophy", "[agrophy, catgirlzeeks]|catgirlzeeks|true");
        step(c, "[MVP+] catgirlzeeks has promoted [VIP] agrophy to Party Leader", "[agrophy, catgirlzeeks]|agrophy|true");
        step(c, "[VIP+] Laru9876 has disconnected, they have 5 minutes to rejoin before they are removed from the party.", "[agrophy, catgirlzeeks]|agrophy|true");
        step(c, "The party was transferred to [MVP+] " + me + " because [MVP++] agrophy left", "[catgirlzeeks]|" + me + "|true");
        step(c, "You are not this party's leader.", "[catgirlzeeks]|null|true");
        step(c, "Kicked catgirlzeeks because they were offline.", "[]|null|true");
        // forged lines in other players' chat change nothing
        step(c, "[VIP] Eve: [MVP+] Mallory has promoted Eve to Party Leader", "[]|null|true");
        step(c, "[VIP] Eve: Mallory joined the party.", "[]|null|true");
        step(c, "Party Finder > Fysiikka joined the dungeon group! (Healer Level 39)", "[Fysiikka]|null|true");
        // the alpha sends party chat with literal section signs
        step(c, "§9Party §8> §a[VIP] agrophy§f: wana f7", "[Fysiikka, agrophy]|null|true");
        say(c, "Party Members (3)");
        say(c, "Party Leader: [VIP] Fysiikka ●");
        say(c, "Party Moderators: [MVP+] DirtToHyperion ●");
        step(c, "Party Members: [MVP+] " + me + " ● ", "[Fysiikka, DirtToHyperion]|Fysiikka|true");
        step(c, "Party Leader, [MVP+] aka_ray, summoned you to their server.", "[Fysiikka, DirtToHyperion, aka_ray]|aka_ray|true");
        step(c, "The party leader, [MVP+] aka_ray has disconnected, they have 5 minutes to rejoin before the party is disbanded.", "[Fysiikka, DirtToHyperion, aka_ray]|aka_ray|true");
        step(c, "You have been kicked from the party by XD_killbot", "[]|null|false");
        step(c, "You have joined [MVP++] yWild's party!", "[yWild]|yWild|true");
        step(c, "You left the party.", "[]|null|false");
        step(c, "You have joined [MVP++] yWild's party!", "[yWild]|yWild|true");
        step(c, "You are not in a party and were moved to the ALL channel.", "[]|null|false");
        step(c, "You have joined [MVP++] yWild's party!", "[yWild]|yWild|true");
        step(c, "You must be in a party to join the party channel!", "[]|null|false");
        step(c, "You have joined [MVP++] yWild's party!", "[yWild]|yWild|true");
        step(c, "[MVP+] " + me + " has been removed from the party.", "[]|null|false");
        step(c, "You have joined [MVP++] yWild's party!", "[yWild]|yWild|true");
        step(c, "[MVP++] yWild has disbanded the party!", "[]|null|false");
        c.note("34 server line forms replayed (main and alpha wording, ranked and plain): members, leader and in-party right after each");
    }

    private static void setWidget(Session c, String... rows) {
        c.hx().tab(List.of(rows), null, null);
        // The widget is read about every second and must repeat; chat is older than the 8 s window in these cases.
    }

    private static void widget(Session c) {
        hub(c);
        String me = self(c);
        step(c, "You are not in a party right now.", "[]|null|false");
        // 11-16: "Party Finder > Killer560 joined the dungeon group!" named only himself; /pl showed four others.
        step(c, "Party Finder > " + me + " joined the dungeon group! (Mage Level 40)", "[]|null|true");
        c.onClient(mc -> { Mod.setField(PT, "chatChangeMs", 0L); return null; });
        setWidget(c, "§b§lParty: §r§f(5/5)", " §aAR3dst0n3E2§f: §bCata 46", " §aDichnes§f: §bCata 45", " §a" + me + "§f: §bCata 45",
                " §aK3YLOW§f: §bCata 50", " §aLaru9876§f: §bCata 44", "§b§lPet:", " §7[Lvl 100] Black Cat");
        c.waitUntil("the tab widget's members reach the tracker", mc -> stateNow().startsWith("[AR3dst0n3E2, Dichnes, K3YLOW, Laru9876]"), 200);
        c.check(state(c).endsWith("|true"), "widget members read but not in a party: " + state(c));
        // a half-listed widget (count says 3, two rows) changes nothing
        setWidget(c, "§b§lParty: §r§f(3/5)", " §aAR3dst0n3E2§f: §bCata 46", " §a" + me + "§f: §bCata 45");
        c.ctx().waitTicks(60);
        c.check(state(c).startsWith("[AR3dst0n3E2, Dichnes, K3YLOW, Laru9876]"), "a half-listed widget changed the members: " + state(c));
        // a fresh chat line beats a widget that has not caught up yet
        say(c, "You have joined [VIP] agrophy's party!");
        setWidget(c, "§b§lParty: §r§fNo party");
        c.ctx().waitTicks(60);
        c.check(state(c).contains("agrophy"), "a widget older than the chat line undid it: " + state(c));
        // once chat is old, "No party" clears (a leave line that never arrived)
        c.onClient(mc -> { Mod.setField(PT, "chatChangeMs", 0L); return null; });
        c.waitUntil("the widget's No party clears the tracker", mc -> stateNow().equals("[]|null|false"), 200);
        setWidget(c, "§b§lParty: §r§fLoading...");
        c.ctx().waitTicks(40);
        c.check(state(c).equals("[]|null|false"), "Loading... changed the state: " + state(c));
        c.note("tab Party widget: members filled in for a Party Finder join, half-list and fresh-chat ignored, No party clears");
    }

    @SuppressWarnings("unchecked")
    private static void readers(Session c) {
        hub(c);
        say(c, "You are not in a party right now.");
        say(c, "You have joined [VIP] agrophy's party!");
        say(c, "You'll be partying with: [VIP] z5x4, [MVP+] MommyDegradeMe");
        List<String> order = c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(PT, "teammatesInLeapOrder")));
        c.check(order.containsAll(List.of("agrophy", "z5x4", "MommyDegradeMe")), "the leap order (Auto Leap targets) misses chat-announced members: " + order);
        say(c, "[MVP+] MommyDegradeMe has left the party.");
        order = c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(PT, "teammatesInLeapOrder")));
        c.check(!order.contains("MommyDegradeMe"), "the leap order still lists a member who left: " + order);
        say(c, "You left the party.");
        c.note("Auto Leap's order follows the tracker through a chat join and leave");
    }
}
