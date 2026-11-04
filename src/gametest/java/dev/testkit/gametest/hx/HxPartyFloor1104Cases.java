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
 *   <li>1104-partycmd-floor-retry: a retry while one waits is not rate limited, and only one /joininstance goes.</li>
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
            HxSocialCases.resetPartyCommandLimits();
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
            run(c, HxSocialCases::resetPartyCommandLimits);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] " + me + "§f: !pt HxMateA");
            c.waitUntil("your own !pt is ignored", mc -> logged(mark, "Ignored your own \"!pt\""), 40);
            assertNoCommand(c, 20, "p transfer");
            c.note("own !f2 -> /" + cmd + "; own !pt ignored");
        }
    }
}
