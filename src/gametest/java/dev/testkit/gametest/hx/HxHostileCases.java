package dev.testkit.gametest.hx;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 150: scenario 86 generalised. EVERY fixture under {@code testkit-fixtures/hostile/} is broadcast twice - as Hypixel
 * delivers other players' chat (system chat, the path every Hypixel line takes) and through vanilla {@code /say}
 * (the signed player-chat path, so {@code ClientReceiveMessageEvents.CHAT} listeners see it too) - with the action-gating
 * features switched on inside an F7 run. Then: the client is still connected, no listener threw (Session's
 * Mod.assertHealthy), the client sent NO command, and a snapshot of every state those lines could forge is unchanged.
 *
 * <p>Tab/overlay hostile fixtures go out through their own channel. Runs last in the session: hostile lines are
 * allowed to leave harmless traces (e.g. a party-chat sender joining PartyTracker, which is right on Hypixel).
 */
final class HxHostileCases {

    private HxHostileCases() {
    }

    static void register(Session s) {
        s.test("150-hx-hostile-broadcast", c -> {
            List<Fixtures.Fixture> hostile = Fixtures.load("hostile");
            c.check(hostile.size() >= 15, "only " + hostile.size() + " hostile fixtures loaded");
            for (Fixtures.Fixture f : hostile) {
                fx(c, f.id());
            }
            Object prince = c.onClient(mc -> Mod.enumValue("interop.PartyInteropState$Flag", "PRINCE_KILLED"));
            Object mimic = c.onClient(mc -> Mod.enumValue("interop.PartyInteropState$Flag", "MIMIC_KILLED"));
            try (Settings set = new Settings(c).with("scorecalc.ScoreCalculatorConfig", "Enabled", true)
                    .with("runstats.RunStatsConfig", "Enabled", true)
                    .with("runsummary.RunSummaryConfig", "Enabled", true)
                    .with("dungeonqueue.DungeonQueueConfig", "Enabled", true)
                    .with("interop.InteropConfig", "Enabled", true)
                    .with("interop.InteropConfig", "ChatParsing", true)
                    .with("puzzlesolvers.QuizSolverConfig", "Enabled", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "Enabled", true)
                    .with("abilitycooldown.AbilityCooldownConfig", "DungeonOnly", false)
                    .with("leapmessage.LeapMessageConfig", "Enabled", true)
                    .with("chatcommands.ChatCommandsConfig", "Enabled", true)
                    .with("partycommands.PartyCommandsConfig", "Enabled", true)
                    .with("playerstats.PlayerStatsConfig", "Enabled", true)) {
                dungeon(c, "secrets", 3);
                run(c, () -> {
                    Mod.staticCall("runstats.RunStatsTracker", "resetRun");
                    Mod.staticCall("runsummary.RunSummaryFeature", "resetRun");
                    Mod.staticCall("interop.PartyInteropState", "reset");
                    Mod.staticCall("abilitycooldown.AbilityCooldownState", "reset");
                    Mod.setField("scorecalc.ScoreCalculatorFeature", "princeKilled", false);
                    Mod.setField("puzzlesolvers.QuizSolverFeature", "triviaAnswers", null);
                    Mod.setField("dungeonqueue.DungeonQueueFeature", "requeuedThisRun", false);
                    Mod.setField("dungeonqueue.DungeonQueueFeature", "disableRequeue", false);
                    Mod.setField("dungeonqueue.DungeonQueueFeature", "pendingTicks", -1);
                });
                // Phase 1: server-only channels (tab, action bar) with overflowing numbers - robustness only, since
                // no other player can write them. Then the dungeon tab back.
                int sent = 0;
                for (Fixtures.Fixture f : hostile) {
                    if (f.kind().equals("overlay") || f.kind().equals("tab")) {
                        send(c, f);
                        sent++;
                        c.ctx().waitTicks(10);
                        assertConnected(c, "hostile fixture " + f.id());
                    }
                }
                dungeon(c, "secrets", 3);
                run(c, () -> Mod.staticCall("abilitycooldown.AbilityCooldownState", "reset"));
                c.ctx().waitTicks(20);
                // Phase 2: everything another player can put in chat, both ways. State must not move.
                Map<String, Object> before = snapshot(c, prince, mimic);
                long cmdsBefore = c.commands().size();
                for (Fixtures.Fixture f : hostile) {
                    if (f.kind().equals("overlay") || f.kind().equals("tab")) {
                        continue;
                    }
                    if (!f.kind().equals("chat")) {
                        throw new AssertionError("no broadcast path for kind " + f.kind() + " (" + f.id() + ")");
                    }
                    send(c, f);
                    c.ctx().waitTicks(6);
                    c.server().command("say " + Fixtures.stripFormatting(f.payload().getAsString()));
                    sent += 2;
                    c.ctx().waitTicks(6);
                    assertConnected(c, "hostile fixture " + f.id());
                }
                c.ctx().waitTicks(40);
                assertConnected(c, "all " + hostile.size() + " hostile fixtures");
                Map<String, Object> after = snapshot(c, prince, mimic);
                List<String> changed = new ArrayList<>();
                for (var e : before.entrySet()) {
                    if (!java.util.Objects.equals(e.getValue(), after.get(e.getKey()))) {
                        changed.add(e.getKey() + ": " + e.getValue() + " -> " + after.get(e.getKey()));
                    }
                }
                List<String> cmds = c.commands().subList((int) cmdsBefore, c.commands().size());
                c.check(cmds.isEmpty(), "hostile lines made the client send " + cmds);
                c.check(changed.isEmpty(), "hostile lines changed mod state: " + changed);
                c.note(sent + " broadcasts of " + hostile.size() + " fixtures; connected; no commands; "
                        + before.size() + " state values unchanged: " + after);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Session c, Object prince, Object mimic) {
        return c.onClient(mc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("floor", Mod.staticCall("secrets.DungeonState", "getFloor"));
            m.put("bossPhase", Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"));
            m.put("scorecalc.princeKilled", Mod.staticCall("scorecalc.ScoreCalculatorFeature", "isPrinceKilled"));
            m.put("interop.PRINCE", Mod.staticCall("interop.PartyInteropState", "flag", prince));
            m.put("interop.MIMIC", Mod.staticCall("interop.PartyInteropState", "flag", mimic));
            m.put("runstats.deaths", Map.copyOf((Map<String, ?>) Mod.field("runstats.RunStatsTracker", "DEATHS")).keySet());
            m.put("runsummary.tracking", Mod.staticCall("runsummary.RunSummaryFeature", "isTrackingRun"));
            m.put("quiz.answers", Mod.field("puzzlesolvers.QuizSolverFeature", "triviaAnswers"));
            m.put("ability.running", List.copyOf((List<?>) Mod.staticCall("abilitycooldown.AbilityCooldownState", "running")));
            m.put("queue.pending", Mod.field("dungeonqueue.DungeonQueueFeature", "pendingTicks"));
            m.put("queue.disable", Mod.field("dungeonqueue.DungeonQueueFeature", "disableRequeue"));
            m.put("splits.runStarted", ((Number) Mod.staticCall("splittimers.SplitTimersFeature", "getRunStartedAtMs")).longValue() > 0);
            m.put("dungeoninfo.secrets", Mod.field("dungeoninfo.DungeonInfoFeature", "lastSecretsCount"));
            m.put("partytracker", List.copyOf((List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates")));
            m.put("nucleus.inLootBlock", Mod.field("mining.nucleus.NucleusRunProfitTracker", "inLootBlock"));
            return m;
        });
    }
}
