package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Party Commands' floor requests (mod partycmd-1104; killer560 2026-11-04: "!f7 seem to be breaking alot"):
 * <ul>
 *   <li>1104-partycmd-floor-cooldown: a teammate's "!f3" inside Creation Cooldown waits it out and is then sent (it was
 *   dropped and still logged "queued F3").</li>
 *   <li>1104-partycmd-floor-retry: a retry while one waits replaces it, and only one /joininstance goes.</li>
 *   <li>1104-partycmd-floor-self: your own "!f2" in party chat queues F2 (it was "Ignored your own"); your own "!pt"
 *   still runs nothing (1103-partycmd-self).</li>
 * </ul>
 */
final class HxPartyFloor1104Cases {

    private static final String PCC = "partycommands.PartyCommandsConfig";
    private static final String CC = "creationcooldown.CreationCooldownFeature";
    private static final String CC_CFG = "creationcooldown.CreationCooldownConfig";

    private HxPartyFloor1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-partycmd-floor-cooldown", HxPartyFloor1104Cases::cooldown);
        s.test("1104-partycmd-floor-retry", HxPartyFloor1104Cases::retry);
        s.test("1104-partycmd-floor-self", HxPartyFloor1104Cases::ownFloor);
        s.test("1106-ptself-transfer", HxPartyFloor1104Cases::ptTransfer);
        s.test("1106-ptself-selftarget", HxPartyFloor1104Cases::ptSelfTarget);
        s.test("1106-ptself-reinv-disband", HxPartyFloor1104Cases::reinvDisband);
        s.test("1106-ptself-rejoin", HxPartyFloor1104Cases::rejoin);
        s.test("1108-partycmd-no-cooldown", HxPartyFloor1104Cases::noCooldown);
        s.test("1118-reinv-member", HxPartyFloor1104Cases::reinvMember);
        s.test("1118-reinv-leader", HxPartyFloor1104Cases::reinvLeader);
        s.test("1118-reinv-kick-rejoin", HxPartyFloor1104Cases::reinvKickRejoin);
        s.test("1118-reinv-off", HxPartyFloor1104Cases::reinvOff);
    }

    // ---- reinv-1118: !reinv leaves (member) / kicks and invites back (leader); rejoin after a kick, not only a disband ----

    private static final String RP = "reparty.RepartyFeature";
    private static final String RP_CFG = "reparty.RepartyConfig";

    private static Settings reinvSettings(Session c, boolean leave, boolean kick) {
        return ptSettings(c).with(RP_CFG, "Enabled", true).with(RP_CFG, "AutoRejoin", false).with(RP_CFG, "LeaveOnReinv", leave)
                .with(RP_CFG, "RejoinOnKick", kick).with(RP_CFG, "ReinvDelay", 2).with(RP_CFG, "RejoinWindow", 60);
    }

    /** Party list lines (leader {@code leader}) read by the leader tracker; a following plain line refreshes Reparty's snapshot. */
    private static void reinvParty(Session c, String leader) {
        ptPrepare(c);
        run(c, () -> Mod.staticCall(RP, "reset"));
        c.hx().chat("Party Leader: [MVP+] " + leader + " ●");
        c.hx().chat("You are in a party.");
        c.ctx().waitTicks(10);
    }

    private static int count(Session c, String cmd) {
        return (int) c.commands().stream().filter(s -> s.equals(cmd)).count();
    }

    /** I type !reinv, someone else leads: /party leave, then the old leader's invite is accepted; a stranger's and a late one are not. */
    private static void reinvMember(Session c) {
        try (Settings set = reinvSettings(c, true, false)) {
            reinvParty(c, "HxMateA");
            c.hx().chat(PARTY_LINE + self(c) + "§f: !reinv");
            awaitCommand(c, "party leave", 80);
            c.check(commandsStarting(c, "p kick").isEmpty(), "a member's own !reinv sent a kick");
            c.hx().chat("§bHxStranger §ehas invited you to join their party!");
            assertNoCommand(c, 30, "party accept HxStranger");
            c.hx().chat("§b[MVP§c+§b] HxMateA §ehas invited you to join their party!");
            c.waitUntil("accepted the old leader's invite", mc -> count(c, "party accept HxMateA") == 1, 80);
            // After the window (real clock, fed through onLine): refused.
            reinvParty(c, "HxMateA");
            long t0 = System.currentTimeMillis();
            run(c, () -> Mod.staticCall(RP, "selfReinv", t0));
            run(c, () -> Mod.staticCall(RP, "onLine", "HxMateA has invited you to join their party!", t0 + 61_000L));
            c.ctx().waitTicks(30);
            c.check(count(c, "party accept HxMateA") == 1, "accepted an invite after the window: " + c.commands());
            c.note("own !reinv -> /party leave; old leader's invite accepted once; stranger and late invite refused");
        }
    }

    /** Someone else's !reinv while I lead: kick, then invite no sooner than the Reinv Delay; a non-member (guild chat) gets nothing. */
    private static void reinvLeader(Session c) {
        try (Settings set = reinvSettings(c, false, false)) {
            ptPrepare(c);
            c.hx().chat("§2Guild > §7HxStranger§f: !reinv");
            assertNoCommand(c, 100, "p kick HxStranger", "p invite HxStranger");
            c.hx().chat(PARTY_LINE + "HxMateA§f: !reinv");
            awaitCommand(c, "p kick HxMateA", 80);
            long kicked = System.currentTimeMillis();
            awaitCommand(c, "p invite HxMateA", 160);
            long gap = System.currentTimeMillis() - kicked;
            c.check(gap >= 1_900L, "invite came " + gap + " ms after the kick, delay is 2000");
            c.note("kick, then invite " + gap + " ms later (delay 2 s); non-member !reinv sent nothing");
            // A member that left on their own: invite only, no kick.
            c.hx().chat(PARTY_LINE + "HxMateB§f: !reinv");
            c.hx().chat("§bHxMateB §ehas left the party.");
            awaitCommand(c, "p invite HxMateB", 160);
            c.check(commandsStarting(c, "p kick HxMateB").isEmpty(), "kicked a member who had already left: " + c.commands());
            c.hx().chat("HxMateB joined the party.");
            c.waitUntil("HxMateB back in the party list", mc -> ((java.util.List<?>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).contains("HxMateB"), 40);
        }
    }

    /** Kicked with no !reinv: an invite from the kicker or a member inside the window is accepted, a stranger's is not. */
    private static void reinvKickRejoin(Session c) {
        try (Settings set = reinvSettings(c, false, true)) {
            reinvParty(c, "HxMateA");
            c.hx().chat("You have been kicked from the party by [MVP+] HxMateA");
            c.hx().chat("§bHxStranger §ehas invited you to join their party!");
            assertNoCommand(c, 30, "party accept HxStranger");
            // A real invite block: dashes, the invite naming a third party leader, the accept hint, dashes.
            long now = System.currentTimeMillis();
            run(c, () -> Mod.staticCall(RP, "onLine", "-----------------------------------------------------\n[MVP+] HxMateB has invited you to join [MVP++] HxMateA's party!\n"
                    + "You have 60 seconds to accept. Click here to join!\n-----------------------------------------------------", now + 2_000L));
            c.waitUntil("accepted a former member's invite after a kick", mc -> count(c, "party accept HxMateB") == 1, 80);
            c.note("kick (no !reinv) then the invite block from a former member -> /party accept; stranger refused");
        }
    }

    /** Each switch OFF sends and accepts nothing. */
    private static void reinvOff(Session c) {
        try (Settings set = reinvSettings(c, false, false)) {
            reinvParty(c, "HxMateA");
            c.hx().chat(PARTY_LINE + self(c) + "§f: !reinv");
            c.hx().chat("You have been kicked from the party by HxMateA");
            c.hx().chat("HxMateA has invited you to join their party!");
            assertNoCommand(c, 60, "party leave", "party accept");
            c.note("Leave On !reinv and Rejoin After Kick OFF: nothing sent");
        }
        try (Settings set = reinvSettings(c, true, true)) {
            reinvParty(c, self(c));
            c.hx().chat(PARTY_LINE + self(c) + "§f: !reinv");
            assertNoCommand(c, 60, "party leave", "p kick");
            c.note("own !reinv as the leader sends nothing");
        }
    }

    private static Settings settings(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(PCC));
        Object queue = c.onClient(mc -> Mod.enumValue(PCC + "$Command", "QUEUE_INSTANCE"));
        Object party = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "PARTY"));
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", queue));
        boolean wasCh = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChannelOn", party));
        return new Settings(c).with(PCC, "Enabled", true).with(CC_CFG, "Enabled", true).with(CC_CFG, "BlockCreation", true)
                .with(CC_CFG, "ChatWhenOver", false)
                .custom(() -> Mod.call(cfg, "setChannelOn", party, true), () -> Mod.call(cfg, "setChannelOn", party, wasCh))
                .custom(() -> Mod.call(cfg, "setOn", queue, true), () -> Mod.call(cfg, "setOn", queue, wasOn));
    }

    private static void prepare(Session c) {
        c.hx().stub(null, "joininstance");
        dungeon(c);
        run(c, () -> {
            Mod.staticCall("partycommands.PartyLeaderTracker", "resetIfSet");
            Mod.staticCall(CC, "testReset");
        });
    }

    private static boolean logged(long mark, String text) {
        return LogTap.since(mark).stream().anyMatch(l -> l.contains(text));
    }

    /** About {@code leftMs} of Creation Cooldown left, as in his log (15 s left at 17:18:37). */
    private static int startCooldown(Session c, long leftMs) {
        run(c, () -> Mod.staticCall(CC, "start", System.currentTimeMillis() - 30_000L + leftMs));
        return c.onClient(mc -> (Integer) Mod.staticCall(CC, "blockedCount"));
    }

    private static void cooldown(Session c) {
        try (Settings set = settings(c)) {
            prepare(c);
            int b0 = startCooldown(c, 4_000L);
            long mark = LogTap.mark();
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: !f3");
            assertNoCommand(c, 20, "joininstance");
            String cmd = awaitCommand(c, "joininstance", 160);
            c.check(cmd.equals("joininstance catacombs_floor_three"), "!f3 sent /" + cmd);
            int b1 = c.onClient(mc -> (Integer) Mod.staticCall(CC, "blockedCount"));
            c.check(b1 == b0, "Creation Cooldown blocked " + (b1 - b0) + " Party Commands send(s)");
            c.check(!logged(mark, "no connection"), "a blocked send was logged as \"no connection\"");
            c.note("!f3 with ~4 s of cooldown left waited it out, then sent /" + cmd);
        } finally {
            run(c, () -> Mod.staticCall(CC, "testReset"));
        }
    }

    private static void retry(Session c) {
        try (Settings set = settings(c)) {
            prepare(c);
            startCooldown(c, 3_000L);
            long mark = LogTap.mark();
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: !f4");
            c.ctx().waitTicks(20);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: !f4");
            c.ctx().waitTicks(10);
            c.check(!logged(mark, "Rate limited HxMateA"), "the retry of a waiting !f4 was rate limited");
            String cmd = awaitCommand(c, "joininstance", 160);
            c.ctx().waitTicks(30);
            List<String> sent = commandsStarting(c, "joininstance");
            c.check(sent.equals(List.of("joininstance catacombs_floor_four")), "!f4 and its retry sent " + sent);
            c.note("retry of a waiting !f4 not rate limited; sent /" + cmd + " once");
        } finally {
            run(c, () -> Mod.staticCall(CC, "testReset"));
        }
    }

    private static void ownFloor(Session c) {
        try (Settings set = settings(c)) {
            prepare(c);
            String me = self(c);
            long mark = LogTap.mark();
            c.hx().chat("§9Party §8> §b[MVP§c+§b] " + me + "§f: !f2");
            String cmd = awaitCommand(c, "joininstance", 60);
            c.check(cmd.equals("joininstance catacombs_floor_two"), "your own !f2 sent /" + cmd);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] " + me + "§f: !pt HxMateA");
            String pt = awaitCommand(c, "p transfer", 60);
            c.check(pt.equals("p transfer HxMateA"), "your own !pt HxMateA sent /" + pt);
            c.note("own !f2 -> /" + cmd + "; own !pt HxMateA -> /" + pt);
        }
    }

    // ---- ptself-1106: own party commands run; !reinv survives a disband and the former party's invites are accepted ----

    private static final String PARTY_LINE = "§9Party §8> §b[MVP§c+§b] ";

    private static Settings ptSettings(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(PCC));
        Object party = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "PARTY"));
        Settings set = new Settings(c).with(PCC, "Enabled", true)
                .custom(() -> Mod.call(cfg, "setChannelOn", party, true), () -> { });
        for (String name : new String[]{"TRANSFER", "REINVITE"}) {
            Object cmd = c.onClient(mc -> Mod.enumValue(PCC + "$Command", name));
            boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", cmd));
            set.custom(() -> Mod.call(cfg, "setOn", cmd, true), () -> Mod.call(cfg, "setOn", cmd, was));
        }
        return set;
    }

    private static void ptPrepare(Session c) {
        c.hx().stub(null, "p");
        dungeon(c);
        run(c, () -> {
            Mod.staticCall("partycommands.PartyLeaderTracker", "resetIfSet");
        });
    }

    /** partymsg-1108: no cooldown on players. A teammate's three commands inside 3 s all go (the old limit took the first
     *  and refused the rest for 8 s with "Ignoring X - they just ran one"), and so do two of yours; no "Ignoring" line. */
    private static void noCooldown(Session c) {
        try (Settings set = ptSettings(c)) {
            ptPrepare(c);
            long mark = LogTap.mark();
            for (int i = 0; i < 3; i++) {
                c.hx().chat(PARTY_LINE + "HxMateA§f: !pt HxMateB");
                c.ctx().waitTicks(8);
            }
            c.hx().chat(PARTY_LINE + self(c) + "§f: !pt HxMateB");
            c.ctx().waitTicks(8);
            c.hx().chat(PARTY_LINE + self(c) + "§f: !pt HxMateB");
            c.waitUntil("five transfers at the server", mc -> commandsStarting(c, "p transfer").size() >= 5, 200);
            c.ctx().waitTicks(30);
            List<String> sent = commandsStarting(c, "p transfer");
            c.check(sent.size() == 5, "5 commands asked inside 3 s sent " + sent);
            c.check(!logged(mark, "Rate limited") && !logged(mark, "Ignoring"), "a cooldown line was logged");
            c.note("three teammate and two own !pt inside 3 s sent " + sent);
        }
    }

    private static void ptTransfer(Session c) {
        try (Settings set = ptSettings(c)) {
            ptPrepare(c);
            c.hx().chat(PARTY_LINE + self(c) + "§f: !pt HxMateB");
            String pt = awaitCommand(c, "p transfer", 60);
            c.check(pt.equals("p transfer HxMateB"), "your own !pt HxMateB sent /" + pt);
            c.note("own !pt HxMateB -> /" + pt);
        }
    }

    private static void ptSelfTarget(Session c) {
        try (Settings set = ptSettings(c)) {
            ptPrepare(c);
            long mark = LogTap.mark();
            c.hx().chat(PARTY_LINE + self(c) + "§f: !pt");
            c.waitUntil("the mod saw your own bare !pt", mc -> logged(mark, "Ignored your own \"!pt\""), 40);
            long mark2 = LogTap.mark();
            c.hx().chat(PARTY_LINE + self(c) + "§f: !pt " + self(c));
            c.waitUntil("the mod saw your own !pt <you>", mc -> logged(mark2, "Ignored your own \"!pt\""), 40);
            assertNoCommand(c, 20, "p transfer");
            c.note("own bare !pt and !pt <own name> send nothing");
        }
    }

    private static void reinvDisband(Session c) {
        try (Settings set = ptSettings(c)) {
            ptPrepare(c);
            long mark = LogTap.mark();
            c.hx().chat(PARTY_LINE + "HxMateA§f: !reinv");
            awaitCommand(c, "p kick HxMateA", 60);
            c.hx().chat("§eThe party was disbanded because all invites expired and the party was empty.");
            String inv = awaitCommand(c, "p invite HxMateA", 200);
            c.check(!logged(mark, "Re-invite canceled"), "the re-invite was canceled by the disband");
            c.note("!reinv after a disband -> /" + inv);
        }
    }

    private static void rejoin(Session c) {
        try (Settings set = ptSettings(c)) {
            ptPrepare(c);
            c.hx().chat(PARTY_LINE + "HxMateA§f: !reinv");
            awaitCommand(c, "p kick HxMateA", 60);
            c.hx().chat("§eThe party was disbanded because all invites expired and the party was empty.");
            awaitCommand(c, "p invite HxMateA", 200);
            c.hx().chat("§r§9-----------------------------------------------------");
            c.hx().chat("§b[MVP§c+§b] HxMateB §ehas invited you to join their party!");
            String acc = awaitCommand(c, "p accept", 60);
            c.check(acc.equals("p accept HxMateB"), "invite from a former member sent /" + acc);
            c.hx().chat("§bHxStranger §ehas invited you to join their party!");
            assertNoCommand(c, 30, "p accept HxStranger");
            c.note("former member's invite -> /" + acc + "; stranger's invite not accepted");
        }
    }
}
