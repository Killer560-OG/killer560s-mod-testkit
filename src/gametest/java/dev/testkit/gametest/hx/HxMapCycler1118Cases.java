package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Map Cycler (mod roomcycle-1118, killer560 2026-11-18: "it should run off of a keybind or /roomcycle in which case it
 * toggles till it finds a room or you press it again"), against the testkit server that records every command. The fake
 * server has no dungeon map, so each step hands the cycler its bounds through {@code MapCyclerFeature.boundsForTest};
 * the bounds themselves are 1118-logic-mapcycler-bounds and 1118-sim-mapcycler-live. A new run is a real hub -> dungeon
 * change (the live map's generation moves). Every re-roll is asserted at the server and on the wire:
 * <ul>
 *   <li>found (max <= N): stops, one line, no command; a proven most within N over a lower least: "Kept: Blood is at
 *       most N room(s) away." (mapcycler-keep-1121, never "on a guess"); least over N: one
 *       {@code /joininstance catacombs_floor_seven} 1 s after the creation cooldown, then nothing while it waits for the
 *       new run;</li>
 *   <li>unsure: nothing before the creation cooldown ends, then a re-roll 1 s after it (When Unsure = Re-roll) or a keep
 *       at its end (Keep);</li>
 *   <li>Mort's run-start line stops it quietly with nothing sent after; outside the Entrance room it does nothing until
 *       he is back in it;</li>
 *   <li>a throttle answer backs off (nothing for 5 s) and retries; Max Re-rolls, the blood door, leaving the dungeon,
 *       the server's "Only the instance creator can re-queue!" and another player's "entered" line each stop it with
 *       nothing sent after;</li>
 *   <li>/roomcycle and the Cycle Keybind toggle the same state.</li>
 * </ul>
 * Legit: 1118-hx-mapcycler-legit-absent (the switch never reads ON, /roomcycle starts nothing).
 */
final class HxMapCycler1118Cases {

    private static final String FEAT = "mapcycler.MapCyclerFeature";
    private static final String CFG = "mapcycler.MapCyclerConfig";
    private static final String CMD = "joininstance catacombs_floor_seven";
    private static final String CC = "creationcooldown.CreationCooldownFeature";

    private HxMapCycler1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-hx-mapcycler-loop", HxMapCycler1118Cases::loop);
        s.test("1118-hx-mapcycler-legit-absent", HxMapCycler1118Cases::legitAbsent);
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(FEAT, "isRunning"));
    }

    private static String said(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.field(FEAT, "lastSaid")));
    }

    private static int sent(Session c) {
        return commandsStarting(c, "joininstance").size();
    }

    private static void bounds(Session c, int min, int max) {
        run(c, () -> Mod.setField(FEAT, "boundsForTest", new int[]{min, max}));
    }

    /** Past Delay Between Re-rolls (30 s) but well inside the 45 s "no new run" window: a 0 here made a SENT re-roll
     *  look 45 s old, so the mod counted a refusal of its own (the 22:21 run of this case). */
    private static void clearDelay(Session c) {
        run(c, () -> Mod.setField(FEAT, "lastSendMs", System.currentTimeMillis() - 31_000L));
    }

    /** The creation cooldown ends {@code fromNowMs} from now (0: now). */
    private static void cooldown(Session c, long fromNowMs) {
        run(c, () -> Mod.setField(CC, "endsAtMs", System.currentTimeMillis() + fromNowMs));
    }

    private static void start(Session c) {
        run(c, () -> Mod.staticCall(FEAT, "setRunning", true));
        c.check(running(c), "setRunning(true) did not start it");
    }

    private static void awaitStop(Session c, String saidPart, int ticks) {
        c.waitUntil("Map Cycler stopped", mc -> !(Boolean) Mod.staticCall(FEAT, "isRunning"), ticks);
        String line = said(c);
        c.check(line.contains(saidPart), "the stop line '" + line + "' does not say '" + saidPart + "'");
    }

    /** No new joininstance for {@code ticks}. */
    private static void quiet(Session c, int ticks, String what) {
        int before = sent(c);
        c.ctx().waitTicks(ticks);
        c.check(sent(c) == before, what + ": " + (sent(c) - before) + " joininstance sent: " + c.commands());
    }

    private static int generation(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("livemap.LiveMapFeature", "resetGeneration"));
    }

    private static void newRun(Session c) {
        int gen = generation(c);
        hub(c);
        dungeon(c);
        c.waitUntil("a new live map generation", mc -> (Integer) Mod.staticCall("livemap.LiveMapFeature", "resetGeneration") != gen, 60);
    }

    private static void loop(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        dev.testkit.harness.WireCapture.describe = p -> p instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket cp
                ? "cmd:" + cp.command() : "-";
        try {
            run(c, () -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setMaxRooms", 5);
                Mod.call(cfg, "setRerollWhenUnsure", true);
                Mod.setField(FEAT, "entranceForTest", true);
                Mod.call(cfg, "setMaxRerolls", 0);
                Mod.call(cfg, "setDelayMs", 30_000);
            });
            dungeon(c);
            dev.testkit.harness.WireCapture.start();

            // Found: max <= N.
            bounds(c, 3, 3);
            start(c);
            awaitStop(c, "Found a map: 3", 40);
            quiet(c, 10, "after a found map");

            // A proven most within N over a lower least: kept as a guarantee, not a guess.
            bounds(c, 2, 5);
            start(c);
            awaitStop(c, "Kept: Blood is at most 5 room(s) away.", 40);
            quiet(c, 10, "after a proven keep");

            // Least over N, the cooldown already over: one re-roll 1 s after the start, then nothing while it waits.
            bounds(c, 7, 9);
            clearDelay(c);
            cooldown(c, 0L);
            long s0 = System.currentTimeMillis();
            start(c);
            c.check(CMD.equals(awaitCommand(c, "joininstance", 60)), "re-roll sent " + c.commands());
            long first = System.currentTimeMillis() - s0;
            c.check(first >= 900, "the re-roll went " + first + " ms after the start (want 1 s past the cooldown)");
            quiet(c, 40, "waiting for the new run");
            c.check(running(c), "it stopped after its re-roll");

            // A new run, unsure: nothing before the creation cooldown ends (5 s), then a re-roll 1 s after it.
            bounds(c, 0, Integer.MAX_VALUE);
            clearDelay(c);
            newRun(c);
            long endsAt = System.currentTimeMillis() + 5_000L;
            run(c, () -> Mod.setField(CC, "endsAtMs", endsAt));
            quiet(c, 80, "unsure, before the creation cooldown ends");
            int before = sent(c);
            c.waitUntil("an unsure re-roll 1 s after the cooldown", mc -> sent(c) > before, 160);
            long late = System.currentTimeMillis() - endsAt;
            c.check(late >= 950 && late <= 2_500, "the unsure re-roll went " + late + " ms after the cooldown ended (want 1 s)");

            // A throttle answer: back off (10 s for the first), then retry.
            c.hx().chat("Command Failed: This command is on cooldown! Try again in about a second!");
            c.waitUntil("one refusal counted", mc -> (Integer) Mod.field(FEAT, "fails") == 1, 40);
            clearDelay(c);
            quiet(c, 100, "backing off after a throttle line");
            int before2 = sent(c);
            c.waitUntil("the retry after the back-off", mc -> sent(c) > before2, 200);

            // Max Re-rolls reached: stops on the next long map, nothing sent.
            int rerolls = c.onClient(mc -> (Integer) Mod.field(FEAT, "rerolls"));
            c.check(rerolls == 3, "re-rolls counted " + rerolls + " (want 3)");
            run(c, () -> Mod.call(cfg, "setMaxRerolls", rerolls));
            bounds(c, 7, 9);
            newRun(c);
            awaitStop(c, "Max Re-rolls (3)", 60);
            quiet(c, 40, "after Max Re-rolls");
            run(c, () -> Mod.call(cfg, "setMaxRerolls", 0));

            // When Unsure = Keep: kept at the deadline, nothing sent.
            run(c, () -> Mod.call(cfg, "setRerollWhenUnsure", false));
            bounds(c, 2, Integer.MAX_VALUE);
            cooldown(c, 3_000L);
            start(c);
            c.ctx().waitTicks(30);
            c.check(running(c), "kept on a guess before the creation cooldown ended: '" + said(c) + "'");
            awaitStop(c, "Kept on a guess (part of the map is unseen): at least 2", 100);
            quiet(c, 20, "after a kept map");
            run(c, () -> Mod.call(cfg, "setRerollWhenUnsure", true));

            // The blood door: stops.
            cooldown(c, 60_000L);
            bounds(c, 0, Integer.MAX_VALUE);
            start(c);
            c.ctx().waitTicks(5);
            c.hx().chat("The BLOOD DOOR has been opened!");
            awaitStop(c, "blood door", 40);

            // The run starts (Mort's line): stops for this dungeon without a word, and nothing goes out after.
            newRun(c);
            bounds(c, 7, 9);
            clearDelay(c);
            cooldown(c, 60_000L);
            start(c);
            String startLine = said(c);
            c.hx().chat("[NPC] Mort: Here, I found this map when I first entered the dungeon.");
            c.waitUntil("the run start stopped it", mc -> !(Boolean) Mod.staticCall(FEAT, "isRunning"), 40);
            c.check(said(c).equals(startLine), "it said '" + said(c) + "' when the run started");
            cooldown(c, 0L);
            quiet(c, 60, "after the run started");

            // Outside the Entrance room: nothing, with the re-roll due; back in it, the re-roll goes.
            newRun(c);
            run(c, () -> Mod.setField(FEAT, "entranceForTest", false));
            cooldown(c, 0L);
            start(c);
            quiet(c, 60, "outside the Entrance room");
            c.check(running(c), "it stopped outside the Entrance room");
            int before6 = sent(c);
            run(c, () -> Mod.setField(FEAT, "entranceForTest", true));
            c.waitUntil("the re-roll back in the Entrance room", mc -> sent(c) > before6, 60);
            run(c, () -> Mod.staticCall(FEAT, "setRunning", false));
            bounds(c, 0, Integer.MAX_VALUE);
            cooldown(c, 60_000L);

            // /roomcycle and the Cycle Keybind toggle the same state.
            newRun(c);
            type(c, "/roomcycle");
            c.waitUntil("/roomcycle started it", mc -> (Boolean) Mod.staticCall(FEAT, "isRunning"), 20);
            Object off = c.onClient(mc -> Mod.staticCall("gui.ModuleKeybinds", "press", "MapCyclerRun"));
            c.check(Boolean.FALSE.equals(off) && !running(c), "the Cycle Keybind did not stop it: " + off);
            Object on = c.onClient(mc -> Mod.staticCall("gui.ModuleKeybinds", "press", "MapCyclerRun"));
            c.check(Boolean.TRUE.equals(on) && running(c), "the Cycle Keybind did not start it: " + on);
            type(c, "/roomcycle");
            awaitStop(c, "Stopped.", 20);

            // Leaving the dungeon: stops 10 s later.
            start(c);
            hub(c);
            awaitStop(c, "You left the dungeon", 300);

            // The server says only the creator can re-queue: one try, then stop.
            dungeon(c);
            cooldown(c, 0L);
            respond(c, "joininstance", null, "Only the instance creator can re-queue!");
            bounds(c, 7, 9);
            clearDelay(c);
            int before3 = sent(c);
            start(c);
            awaitStop(c, "Only the player who started the run", 80);
            c.check(sent(c) == before3 + 1, "want one re-roll before the refusal, got " + (sent(c) - before3));
            quiet(c, 40, "after the creator refusal");

            // Someone else made the run ("entered" line): stops without sending.
            c.hx().chat("[MVP+] HxMateA entered The Catacombs, Floor VII!");
            c.waitUntil("Creation Cooldown read the creator", mc -> "HxMateA".equals(Mod.staticCall("creationcooldown.CreationCooldownFeature", "creator")), 40);
            clearDelay(c);
            int before4 = sent(c);
            start(c);
            awaitStop(c, "Only HxMateA", 40);
            quiet(c, 20, "after another player's run");
            c.check(sent(c) == before4, "sent a re-roll in another player's run");

            dev.testkit.harness.WireCapture.stop();
            List<Long> at = new ArrayList<>();
            for (dev.testkit.harness.WireCapture.Sent w : dev.testkit.harness.WireCapture.snapshot()) {
                if (w.text().startsWith("cmd:joininstance")) {
                    at.add(w.nanos());
                }
            }
            c.check(at.size() == sent(c), "wire saw " + at.size() + " joininstance, the server " + sent(c));
            long closest = Long.MAX_VALUE;
            for (int i = 1; i < at.size(); i++) {
                closest = Math.min(closest, (at.get(i) - at.get(i - 1)) / 1_000_000L);
            }
            c.check(at.size() < 2 || closest >= 1_000, "two re-rolls " + closest + " ms apart");
            c.note("re-rolls at the server: " + sent(c) + " (found, least>N, unsure, retry, creator answer); closest "
                    + (at.size() < 2 ? "-" : closest + " ms") + " apart; every stop sent nothing after it");
        } finally {
            dev.testkit.harness.WireCapture.stop();
            dev.testkit.harness.WireCapture.describe = null;
            c.hx().call("surface.respond.clear", Hx.args("root", "joininstance"));
            run(c, () -> {
                Mod.staticCall(FEAT, "setRunning", false);
                Mod.setField(FEAT, "boundsForTest", null);
                Mod.setField(FEAT, "entranceForTest", null);
                Mod.setField("creationcooldown.CreationCooldownFeature", "creator", null);
                Mod.setField("creationcooldown.CreationCooldownFeature", "endsAtMs", 0L);
                Mod.call(cfg, "setEnabled", false);
            });
            hub(c);
        }
    }

    private static void legitAbsent(Session c) {
        if (Mod.isCheat()) {
            c.note("cheat jar: covered by 1118-hx-mapcycler-loop");
            return;
        }
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        try {
            run(c, () -> Mod.call(cfg, "setEnabled", true));
            c.check(!c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled")), "the switch reads ON in a legit jar");
            dungeon(c);
            type(c, "/roomcycle");
            c.ctx().waitTicks(20);
            c.check(!running(c), "/roomcycle started it in a legit jar");
            c.check(sent(c) == 0, "a legit jar sent " + c.commands());
        } finally {
            run(c, () -> Mod.call(cfg, "setEnabled", false));
            hub(c);
        }
    }
}
