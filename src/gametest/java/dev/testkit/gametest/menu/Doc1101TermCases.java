package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Doc items of 2026-11-01 (mod doc-terms-1101): Terminal Solver, Auto Terminals Stream Safe, Team Melody, Auto Sprint.
 * <ul>
 *   <li>1101-menu-term-instcfg: with whatever config the client was SEEDED with (run it with -PseedConfig pointing at a
 *       copy of an instance's config/), nothing overridden, every terminal type is opened on the server and the solver
 *       must track it and highlight something. Prints the loaded settings so a run proves which config it read.</li>
 * </ul>
 */
final class Doc1101TermCases {

    static final String CONFIG = TerminalCases.CONFIG;

    private Doc1101TermCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-menu-term-instcfg", Doc1101TermCases::instCfg);
        MenuSuite.test(s, "1101-menu-term-mousedrop-vanilla", c -> mouseDrop(c, false));
        MenuSuite.test(s, "1101-menu-term-mousedrop-custom", c -> mouseDrop(c, true));
        MenuSuite.test(s, "1101-menu-term-invtheme", Doc1101TermCases::invTheme);
        MenuSuite.test(s, "1101-menu-term-human300-panes", c -> human300(c, TerminalCases.SPECS.get(0)));
        MenuSuite.test(s, "1101-menu-term-human300-rubix", c -> human300(c, TerminalCases.SPECS.get(1)));
        MenuSuite.test(s, "1101-menu-term-human-melody-preaim", Doc1101TermCases::melodyPreAim);
        MenuSuite.test(s, "1101-menu-term-hover-gap300", c -> hoverGap(c, 300));
        MenuSuite.test(s, "1101-menu-term-hover-gap0", c -> hoverGap(c, 0));
        MenuSuite.test(s, "1103-menu-term-firstdelay-fake", c -> firstDelay(c, true));
        MenuSuite.test(s, "1103-menu-term-firstdelay-minmax", c -> firstDelay(c, false));
        MenuSuite.test(s, "1101-menu-melody-intel", Doc1101TermCases::melodyIntel);
        MenuSuite.test(s, "1101-menu-autosprint", Doc1101TermCases::autoSprint);
    }

    static final String SPRINT_CFG = "autosprint.AutoSprintConfig";
    static final String SPRINT = "autosprint.AutoSprintFeature";
    static final String SHORT = "pathfinding.ShortWalk";

    /** Back on the platform's spawn block, facing +z (six blocks of platform ahead), standing still. */
    static void toSpawn(Session c) {
        c.hx().call("dungeon.tp", "x", MenuSuite.SPAWN_X, "y", MenuSuite.SPAWN_Y, "z", MenuSuite.SPAWN_Z, "yaw", 0f, "pitch", 0f);
        c.waitUntil("him standing on the spawn", mc -> mc.player.onGround() && Math.abs(mc.player.getX() - MenuSuite.SPAWN_X) < 0.5
                && Math.abs(mc.player.getZ() - MenuSuite.SPAWN_Z) < 0.5, 100);
        c.ctx().waitTicks(3);
    }

    static boolean sprinting(Session c) {
        return c.onClient(mc -> mc.player.isSprinting());
    }

    static void stopSprint(Session c) {
        c.ctx().getInput().releaseKey(o -> o.keyUp);
        c.onClient(mc -> {
            mc.player.setSprinting(false);
            return null;
        });
        c.ctx().waitTicks(10);
    }

    /**
     * Auto Sprint (mod doc-terms-1101), on the menu platform:
     * <ol>
     *   <li>control, Auto Sprint OFF: holding forward for 10 ticks does not sprint;</li>
     *   <li>Auto Sprint ON: holding forward sprints within 5 ticks, and the feature counted the ticks it added the key;</li>
     *   <li>a mod mover drives (a ShortWalk with forward down, sprint up) with Auto Sprint ON: it adds nothing (its tick
     *       counter does not move) and he does not sprint;</li>
     *   <li>the same drive with a "foreign auto sprint" calling setSprinting(true) every tick (what another mod's auto
     *       sprint does): he never sprints and the refusals are counted;</li>
     *   <li>control: with no mover, the same setSprinting(true) does make him sprint, so the refusal in 4 is the mixin.</li>
     * </ol>
     * The session's anticheat verdict covers every step.
     */
    static void autoSprint(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> notes = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(SPRINT_CFG, "Enabled", false);
            toSpawn(c);
            stopSprint(c);
            c.ctx().getInput().holdKey(o -> o.keyUp);
            c.ctx().waitTicks(10);
            boolean offSprint = sprinting(c);
            c.check(!offSprint, "control: sprinting with Auto Sprint OFF and only forward held");
            stopSprint(c);
            notes.add("OFF + forward: sprinting=" + offSprint);

            toSpawn(c);
            c.onClient(mc -> {
                Mod.set(SPRINT_CFG, "setEnabled", true);
                return null;
            });
            int ticks0 = c.onClient(mc -> (Integer) Mod.field(SPRINT, "sprintTicks"));
            c.ctx().getInput().holdKey(o -> o.keyUp);
            int waited = 0;
            while (!sprinting(c) && waited < 20) {
                c.ctx().waitTicks(1);
                waited++;
            }
            boolean onSprint = sprinting(c);
            int added = c.onClient(mc -> (Integer) Mod.field(SPRINT, "sprintTicks")) - ticks0;
            c.check(onSprint, "Auto Sprint ON + forward did not sprint in 20 ticks");
            c.check(added > 0, "Auto Sprint never added the sprint key");
            stopSprint(c);
            notes.add("ON + forward: sprinting after " + waited + " tick(s), key added " + added + " tick(s)");

            // A mod mover driving forward with sprint up.
            toSpawn(c);
            Object plan = c.onClient(mc -> Mod.staticCall(SHORT, "plan", mc.level, mc.player,
                    mc.player.blockPosition().offset(3, 0, 0), null));
            c.check(plan != null, "ShortWalk could not plan a 3-block walk on the platform");
            if (plan == null) {
                return;
            }
            int ticks1 = c.onClient(mc -> (Integer) Mod.field(SPRINT, "sprintTicks"));
            int blocked0 = c.onClient(mc -> (Integer) Mod.field(SPRINT, "startsBlocked"));
            c.onClient(mc -> {
                Mod.staticCall(SHORT, "start", plan, "testkit");
                Mod.setField(SHORT, "wantForward", true);
                Mod.setField(SHORT, "wantSprint", false);
                return null;
            });
            boolean everSprint = false;
            for (int i = 0; i < 8; i++) {
                c.ctx().waitTicks(1);
                everSprint |= sprinting(c);
            }
            int addedWhileDriving = c.onClient(mc -> (Integer) Mod.field(SPRINT, "sprintTicks")) - ticks1;
            c.check(addedWhileDriving == 0, "Auto Sprint added the sprint key " + addedWhileDriving + " time(s) under a mod mover");
            c.check(!everSprint, "sprinted while a mod mover drove with sprint up (Auto Sprint ON)");
            // Now another mod's auto sprint: setSprinting(true) every tick.
            boolean foreignSprint = false;
            for (int i = 0; i < 8; i++) {
                c.onClient(mc -> {
                    mc.player.setSprinting(true);
                    return null;
                });
                c.ctx().waitTicks(1);
                foreignSprint |= sprinting(c);
            }
            int blocked = c.onClient(mc -> (Integer) Mod.field(SPRINT, "startsBlocked")) - blocked0;
            c.onClient(mc -> {
                Mod.staticCall(SHORT, "stop", "testkit");
                return null;
            });
            c.check(!foreignSprint, "a foreign setSprinting(true) made him sprint while a mod mover drove with sprint up");
            c.check(blocked > 0, "no sprint start was refused under the mover");
            notes.add("mover driving, sprint up: Auto Sprint added " + addedWhileDriving + ", sprinting=" + everSprint
                    + "; foreign setSprinting(true) x8: sprinting=" + foreignSprint + ", refused " + blocked);
            stopSprint(c);

            // Control: no mover, the same foreign call works.
            toSpawn(c);
            c.onClient(mc -> {
                Mod.set(SPRINT_CFG, "setEnabled", false);
                return null;
            });
            c.ctx().getInput().holdKey(o -> o.keyUp);
            c.ctx().waitTicks(2);
            c.onClient(mc -> {
                mc.player.setSprinting(true);
                return null;
            });
            c.ctx().waitTicks(1);
            boolean control = sprinting(c);
            c.check(control, "control: setSprinting(true) with no mover did not sprint, so case 4 proves nothing");
            notes.add("control, no mover: foreign setSprinting(true) -> sprinting=" + control);
            stopSprint(c);
        } finally {
            c.ctx().getInput().releaseKey(o -> o.keyUp);
            c.onClient(mc -> {
                try {
                    Mod.staticCall(SHORT, "stop", "testkit");
                } catch (AssertionError ignored) {
                }
                return null;
            });
            MenuKit.reset(c);
        }
        c.note(String.join("; ", notes));
    }

    static final String INTEL = "bridge.MelodyIntel";
    static final String TRACKER = "melody.MelodyTrackerFeature";

    static int percent(Object progress) {
        return progress == null ? Integer.MIN_VALUE : (Integer) Mod.call(progress, "percent");
    }

    /**
     * Team Melody's store (mod doc-terms-1101), driven directly: a teammate's board (clay row, target, moving column) reads
     * as a board, not 100%; their "activated a terminal!" line while the entry is live turns it to 100%; an entry with no
     * update for 15 s is gone; a chat-only callout reads as a percent with no board; an Odin close with no completion line
     * is gone after 2.5 s, and with one it reads 100%; a P3 section change clears everything; the relay's new done type (9)
     * is accepted by the protocol reader and an unknown type still is not. The chat patterns match the real line shapes
     * and refuse a forged prefix.
     */
    static void melodyIntel(Session c) throws Exception {
        List<String> notes = new ArrayList<>();
        c.onClient(mc -> {
            Mod.staticCall(INTEL, "clear");
            long now = System.currentTimeMillis();
            Mod.staticCall(INTEL, "offer", "Mate_One", 1, 2);
            Mod.staticCall(INTEL, "offer", "Mate_One", 2, 3);
            Mod.staticCall(INTEL, "offer", "Mate_One", 5, 1);
            Object p = Mod.staticCall(INTEL, "get", "mate_one");
            c.check(p != null && (Boolean) Mod.call(p, "hasBoard"), "a relay/Odin source should carry a board: " + p);
            int before = percent(p);
            c.check(before > 0 && before < 100, "row 2 should read between 0 and 100%, got " + before);
            Mod.staticCall(INTEL, "onTerminalCompleted", "Mate_One");
            int after = percent(Mod.staticCall(INTEL, "get", "Mate_One"));
            c.check(after == 100, "after the completion line Mate_One reads " + after + "%, not 100%");
            notes.add("board row 2: " + before + "% -> completion line -> " + after + "%");
            Mod.staticCall(INTEL, "expire", now + 14_000L);
            c.check(Mod.staticCall(INTEL, "get", "Mate_One") != null, "a done entry vanished before 15 s");
            Mod.staticCall(INTEL, "expire", now + 15_500L);
            c.check(Mod.staticCall(INTEL, "get", "Mate_One") == null, "an entry still there 15.5 s after its last update");
            notes.add("done entry kept at 14 s, gone at 15.5 s");

            Mod.staticCall(INTEL, "offerChat", "Chat_Guy", 33);
            Object chat = Mod.staticCall(INTEL, "get", "Chat_Guy");
            c.check(chat != null && !(Boolean) Mod.call(chat, "hasBoard") && percent(chat) == 33,
                    "a chat callout should read 33% with no board: " + chat);
            notes.add("chat-only: " + percent(chat) + "%, board=" + Mod.call(chat, "hasBoard"));

            Mod.staticCall(INTEL, "offer", "Odin_User", 1, 1);
            Mod.staticCall(INTEL, "offer", "Odin_User", 0, 0);
            Mod.staticCall(INTEL, "expire", System.currentTimeMillis() + 1_000L);
            c.check(Mod.staticCall(INTEL, "get", "Odin_User") != null, "an Odin close was dropped before its 2.5 s grace");
            Mod.staticCall(INTEL, "expire", System.currentTimeMillis() + 3_000L);
            c.check(Mod.staticCall(INTEL, "get", "Odin_User") == null, "an Odin close with no completion was kept past 2.5 s");
            Mod.staticCall(INTEL, "offer", "Odin_Two", 1, 1);
            Mod.staticCall(INTEL, "offer", "Odin_Two", 0, 0);
            Mod.staticCall(INTEL, "onTerminalCompleted", "Odin_Two");
            Mod.staticCall(INTEL, "expire", System.currentTimeMillis() + 3_000L);
            int odinDone = percent(Mod.staticCall(INTEL, "get", "Odin_Two"));
            c.check(odinDone == 100, "an Odin close followed by its completion line reads " + odinDone + "%");
            notes.add("Odin close: dropped after 2.5 s without a completion line, 100% with one");

            Mod.staticCall(INTEL, "offer", "Relay_Mate", 1, 3);
            Mod.staticCall(INTEL, "offer", "Relay_Mate", 9, 0);
            int relayDone = percent(Mod.staticCall(INTEL, "get", "Relay_Mate"));
            c.check(relayDone == 100, "the relay's done type left Relay_Mate at " + relayDone + "%");
            Mod.staticCall(INTEL, "clear");
            c.check(((List<?>) Mod.staticCall(INTEL, "snapshot")).isEmpty(), "clear() left entries");

            com.google.gson.JsonObject done = new com.google.gson.JsonObject();
            done.addProperty("type", 9);
            done.addProperty("slot", 0);
            Object type = Mod.staticCall("partydata.PartyDataProtocol", "readMelodyType", done);
            c.check(Integer.valueOf(9).equals(type), "the protocol reader refused the done type: " + type);
            com.google.gson.JsonObject odd = new com.google.gson.JsonObject();
            odd.addProperty("type", 3);
            odd.addProperty("slot", 0);
            c.check(Mod.staticCall("partydata.PartyDataProtocol", "readMelodyType", odd) == null, "type 3 was accepted");
            notes.add("protocol: type 9 accepted, type 3 refused");

            java.util.regex.Pattern doneLine = Mod.pattern(TRACKER, "TERMINAL_DONE");
            java.util.regex.Pattern party = Mod.pattern(TRACKER, "PARTY_LINE");
            java.util.regex.Pattern progress = Mod.pattern(TRACKER, "PROGRESS");
            c.check(doneLine.matcher("Mate_One activated a terminal! (3/7)").matches(), "the completion line did not match");
            c.check(!doneLine.matcher("[VIP] Bob: a activated a terminal! (3/7)").matches(), "a forged completion line matched");
            String[] lines = {"Party > [MVP+] Mate_One: Melody 1/3 - 33%", "Party > Mate_One: Melody 67%", "Party > Mate_One: mel 2/4"};
            for (String line : lines) {
                java.util.regex.Matcher m = party.matcher(line);
                c.check(m.matches() && progress.matcher(m.group(2)).find(), "a real progress line did not parse: " + line);
            }
            java.util.regex.Matcher noise = party.matcher("Party > Mate_One: 1/3 of us are afk");
            c.check(noise.matches() && !progress.matcher(noise.group(2)).find(), "a line with no Melody word parsed as progress");
            notes.add("chat patterns: completion and three progress shapes match, forged prefix and a bare 1/3 do not");
            return null;
        });
        c.note(String.join("; ", notes));
    }

    static final String CURSOR = "terminals.FakeCursor";

    /**
     * First Click Delay (2026-11-03): 0, 150 and 300 ms, in Stream Safe and in Min/Max mode. The first click of a Panes
     * terminal comes no earlier than the 500 ms opening settle plus the delay after it opened (server ticks of 50 ms, one
     * tick of slack each side), and not absurdly later; a click actually happened and the terminal solved with none wrong.
     * The setting also reads back, and the 300 ms first click comes at least ~4 ticks after the 0 ms one.
     */
    static void firstDelay(Session c, boolean fake) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        TerminalCases.Spec spec = TerminalCases.SPECS.get(0);
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long[] firstTicks = new long[3];
        int[] delays = {0, 150, 300};
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "CursorMissChance", 0).set(CONFIG, "CursorOvershootChance", 0)
                    .set(CONFIG, "CursorReactionMs", 60).set(CONFIG, "FakeCursorSpeed", 300).set(CONFIG, "AutoStreamSafe", fake);
            for (int i = 0; i < delays.length; i++) {
                int d = delays[i];
                cfg.set(CONFIG, "FirstClickDelayMs", d);
                int back = c.onClient(mc -> (Integer) Mod.get(CONFIG, "getFirstClickDelayMs"));
                if (back != d) {
                    problems.add("First Click Delay " + d + " read back as " + back);
                }
                int solvedBefore = c.events("terminal.solved").size();
                int before = c.events("terminal.click").size();
                JsonObject t = TerminalCases.open(c, spec);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("delay " + d + " solved", mc -> c.events("terminal.solved").size() > solvedBefore, 900);
                JsonObject solved = c.events("terminal.solved").get(c.events("terminal.solved").size() - 1);
                MenuKit.awaitNoScreen(c, 60);
                c.ctx().waitTicks(5);
                List<JsonObject> evs = c.events("terminal.click");
                if (evs.size() <= before) {
                    problems.add("delay " + d + ": no click happened");
                    continue;
                }
                if (solved.get("wrong").getAsInt() > 0) {
                    problems.add("delay " + d + ": " + solved.get("wrong").getAsInt() + " wrong clicks");
                }
                long first = evs.get(before).get("tick").getAsLong() - solved.get("openedTick").getAsLong();
                firstTicks[i] = first;
                long min = (500 + d) / 50 - 1;
                long max = (500 + d) / 50 + 2 + (fake ? 40 : 6);
                if (first < min || first > max) {
                    problems.add("delay " + d + ": first click " + first + " ticks after opening, expected " + min + ".." + max);
                }
                notes.add("delay " + d + ": first click " + first + " ticks (" + (first * 50) + " ms) after opening");
            }
            if (firstTicks[0] > 0 && firstTicks[2] > 0 && firstTicks[2] - firstTicks[0] < 4) {
                problems.add("300 ms first click only " + (firstTicks[2] - firstTicks[0]) + " ticks later than 0 ms");
            }
        } finally {
            MenuKit.reset(c);
        }
        c.note((fake ? "Stream Safe" : "Min/Max") + ": " + notes);
        c.check(problems.isEmpty(), "problems: " + problems);
    }


    /**
     * Stream Safe at the new top speed (300%): the setting takes 300 (it was capped at 200), two solves with zero wrong
     * clicks, every click with the drawn cursor inside the clicked slot, the hand still moving after each press (the glide,
     * summed px in the 150 ms after each click, must be above zero), and the first click's time from the opening noted.
     * Rubix: each pane exactly its count, back to back, the cursor never leaving a pane between two clicks on it.
     */
    static void human300(Session c, TerminalCases.Spec spec) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "CursorMissChance", 6).set(CONFIG, "CursorOvershootChance", 18)
                    .set(CONFIG, "CursorReactionMs", 130).set(CONFIG, "FakeCursorSpeed", 300).set(CONFIG, "AutoStreamSafe", true);
            int speedSet = c.onClient(mc -> (Integer) Mod.get(CONFIG, "getFakeCursorSpeed"));
            c.check(speedSet == 300, "Cursor Speed 300 read back as " + speedSet);
            for (int run = 0; run < 2; run++) {
                int solvedBefore = c.events("terminal.solved").size();
                int before = c.events("terminal.click").size();
                JsonObject t = TerminalCases.open(c, spec);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil(spec.type() + " run " + run + " solved", mc -> c.events("terminal.solved").size() > solvedBefore, 900);
                JsonObject solved = c.events("terminal.solved").get(c.events("terminal.solved").size() - 1);
                MenuKit.awaitNoScreen(c, 60);
                c.ctx().waitTicks(5);
                int wrong = solved.get("wrong").getAsInt();
                if (wrong > 0) {
                    problems.add("run " + run + ": " + wrong + " wrong");
                }
                int count = TermHumanCases.intField(c, CURSOR, "clickCount");
                int server = c.events("terminal.click").size() - before;
                if (count != server) {
                    problems.add("run " + run + ": cursor recorded " + count + " clicks, server saw " + server);
                }
                float[] xs = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_X")).clone());
                float[] ys = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_Y")).clone());
                float[] txs = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_TX")).clone());
                float[] tys = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_TY")).clone());
                float[] halves = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_HALF")).clone());
                for (int i = 0; i < Math.min(count, xs.length); i++) {
                    if (Math.abs(xs[i] - txs[i]) > halves[i] + 0.6 || Math.abs(ys[i] - tys[i]) > halves[i] + 0.6) {
                        problems.add(String.format("run %d click %d: cursor (%.1f, %.1f) outside its slot (%.1f, %.1f +-%.1f)",
                                run, i, xs[i], ys[i], txs[i], tys[i], halves[i]));
                    }
                }
                float glide = TermHumanCases.floatField(c, CURSOR, "glideAfterClickPx");
                long first = c.onClient(mc -> (Long) Mod.field(CURSOR, "firstClickAfterOpenMs"));
                float speed = TermHumanCases.floatField(c, CURSOR, "maxPxPerMs");
                if (!(glide > 0f)) {
                    problems.add("run " + run + ": the cursor never moved after a click (glide " + glide + " px)");
                }
                if (spec.type().equals("RUBIX")) {
                    float stray = TermHumanCases.floatField(c, CURSOR, "repeatMaxPx");
                    if (stray > 1f) {
                        problems.add("run " + run + ": the cursor left a pane between two clicks on it (" + stray + " px out)");
                    }
                }
                long took = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
                notes.add(String.format("run %d: %d ticks, %d clicks, first click %d ms after opening, glide %.0f px, top speed %.2f px/ms",
                        run, took, server, first, glide, speed));
            }
        } finally {
            MenuKit.reset(c);
        }
        c.note(spec.type() + " at 300%: " + notes);
        c.check(problems.isEmpty(), "problems: " + problems);
    }

    /**
     * Stream Safe Melody: the hand goes to the button side at once, before any row is due. Within 12 ticks of the board
     * opening (still inside the opening settle, before any click is allowed) the cursor must be aimed at one of the board's
     * row buttons, and the solve must finish with zero wrong clicks.
     */
    static void melodyPreAim(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "HoldToClickEnabled", false)
                    .set(CONFIG, "MelodyLookaheadClicks", 0).set(CONFIG, "FakeCursorSpeed", 100).set(CONFIG, "AutoStreamSafe", true);
            int solvedBefore = c.events("terminal.solved").size();
            int clicksBefore = c.events("terminal.click").size();
            JsonObject t = TerminalCases.open(c, TerminalCases.MELODY_3);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track MELODY", mc -> "MELODY".equals(TerminalCases.currentType()), 100);
            int aimed = -1;
            int ticks = 0;
            int[] buttons = null;
            for (; ticks < 12; ticks++) {
                aimed = c.onClient(mc -> (Integer) Mod.field(CURSOR, "aimedSlot"));
                buttons = c.onClient(mc -> ((int[]) Mod.field(FEATURE, "melodyButtons")).clone());
                if (aimed >= 0) {
                    break;
                }
                c.ctx().waitTicks(1);
            }
            int clicksAtAim = c.events("terminal.click").size() - clicksBefore;
            boolean onButton = false;
            for (int b : buttons) {
                onButton |= b == aimed;
            }
            c.check(onButton, "the cursor was not aimed at a row button within 12 ticks (aimed " + aimed + ", buttons "
                    + java.util.Arrays.toString(buttons) + ")");
            c.waitUntil("Melody solved", mc -> c.events("terminal.solved").size() > solvedBefore, 1200);
            JsonObject solved = c.events("terminal.solved").get(c.events("terminal.solved").size() - 1);
            c.check(solved.get("wrong").getAsInt() == 0, "Melody wrong clicks: " + solved);
            c.note("cursor aimed at row button " + aimed + " " + ticks + " tick(s) after tracking, " + clicksAtAim
                    + " click(s) sent by then; solved in " + (solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong())
                    + " ticks, 0 wrong");
        } finally {
            MenuKit.reset(c);
        }
    }

    static final String FEATURE = TerminalCases.FEATURE;

    /**
     * Hover Terminals' delay is the least time BETWEEN hover clicks, not a dwell (2026-11-01, max 300 ms). Panes, Custom GUI:
     * pointing at a correct slot clicks it within 3 ticks (no dwell), pointing at the next correct slot right after must wait
     * for the delay since the first click (server ticks between the two clicks >= delay - 1 tick), and with delay 0 the two
     * clicks still never share a server tick.
     */
    static void hoverGap(Session c, int delay) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Hover Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", true)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "HoverTerminalsEnabled", true)
                    .set(CONFIG, "HoverDelayMs", delay).set(CONFIG, "HoverJitterMs", 0)
                    .set(QOL, "ProtectionEnabled", false);
            int readBack = c.onClient(mc -> (Integer) Mod.get(CONFIG, "getHoverDelayMs"));
            c.check(readBack == delay, "Hover Delay " + delay + " read back as " + readBack);
            c.ctx().getInput().setCursorPos(2, 2);
            JsonObject t = TerminalCases.open(c, TerminalCases.SPECS.get(0));
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to highlight", mc -> TerminalCases.highlights().size() >= 3, 100);
            c.ctx().waitTicks(20);
            List<Integer> slots = c.onClient(mc -> new ArrayList<>(TerminalCases.highlights().keySet()));
            int before = c.events("terminal.click").size();
            TermHoldCases.pointAt(c, slots.get(0), true);
            int waited = 0;
            while (c.events("terminal.click").size() == before && waited < 40) {
                c.ctx().waitTicks(1);
                waited++;
            }
            c.check(c.events("terminal.click").size() > before, "pointing at a correct slot never clicked it");
            c.check(waited <= 3, "the first hover click took " + waited + " ticks (a dwell?)");
            TermHoldCases.pointAt(c, slots.get(1), true);
            c.waitUntil("the second hover click", mc -> c.events("terminal.click").size() > before + 1, 60);
            List<JsonObject> evs = c.events("terminal.click");
            long t1 = evs.get(before).get("tick").getAsLong();
            long t2 = evs.get(before + 1).get("tick").getAsLong();
            long gapTicks = t2 - t1;
            int min = Math.max(1, delay / 50 - 1);
            c.check(gapTicks >= min, "two hover clicks " + gapTicks + " server ticks apart with Hover Delay " + delay + " ms");
            c.check(evs.get(before).get("correct").getAsBoolean() && evs.get(before + 1).get("correct").getAsBoolean(),
                    "a hover click was wrong: " + evs.subList(before, before + 2));
            c.note("Hover Delay " + delay + " ms: first click " + waited + " tick(s) after pointing, second " + gapTicks
                    + " server tick(s) after the first");
        } finally {
            MenuKit.reset(c);
        }
    }

    static final String QOL = "terminals.TerminalQolConfig";

    /**
     * Terminal Drop Key on a MOUSE button (stored code -103 = button 3, "M4"): pointing at a correct Panes slot and pressing
     * that button must make the server see exactly one click on that slot (THROW with Custom GUI off, the panel's own
     * redirect with it on), and the mod must count it as a mouse drop. Without the binding (control) the same press does
     * nothing on the vanilla screen.
     */
    static void mouseDrop(Session c, boolean customGui) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "CustomGuiEnabled", customGui)
                    .set(QOL, "DropKeyEnabled", true).set(QOL, "ProtectionEnabled", false).set(QOL, "DropKeyCode", -103);
            TerminalCases.Spec spec = TerminalCases.SPECS.get(0);
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to highlight", mc -> !TerminalCases.highlights().isEmpty(), 100);
            c.ctx().waitTicks(10);
            int slot = c.onClient(mc -> TerminalCases.highlights().keySet().iterator().next());
            TermHoldCases.pointAt(c, slot, customGui);
            c.ctx().waitTicks(3);
            int drops0 = c.onClient(mc -> (Integer) Mod.field("terminals.TerminalQolFeature", "mouseDrops"));
            int before = c.events("terminal.click").size();
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(3));
            c.waitUntil("the server to see the drop-key click", mc -> c.events("terminal.click").size() > before, 60);
            c.ctx().waitTicks(5);
            List<JsonObject> evs = c.events("terminal.click");
            JsonObject e = evs.get(before);
            int drops = c.onClient(mc -> (Integer) Mod.field("terminals.TerminalQolFeature", "mouseDrops")) - drops0;
            c.check(evs.size() - before == 1, "one press made " + (evs.size() - before) + " server click(s)");
            c.check(e.get("slot").getAsInt() == slot, "the click landed on slot " + e.get("slot") + ", pointed at " + slot);
            c.check(e.get("correct").getAsBoolean(), "the server counted the drop-key click wrong: " + e);
            c.check(drops == 1, "the mod counted " + drops + " mouse drop(s)");
            if (!customGui) {
                c.check("THROW".equals(e.get("input").getAsString()), "vanilla screen: expected a THROW click, got " + e.get("input"));
            }
            c.note("M4 as drop key, Custom GUI " + (customGui ? "on" : "off") + ": one server click on " + slot + " ("
                    + e.get("input").getAsString() + ", correct), mouseDrops +" + drops);
            // Control: the binding off, the same press on the vanilla screen sends nothing.
            if (!customGui) {
                c.onClient(mc -> {
                    Mod.set(QOL, "setDropKeyCode", -1);
                    return null;
                });
                int slot2 = c.onClient(mc -> TerminalCases.highlights().keySet().stream().filter(x -> x != slot).findFirst().orElse(-1));
                TermHoldCases.pointAt(c, slot2, false);
                c.ctx().waitTicks(3);
                int b2 = c.events("terminal.click").size();
                c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(3));
                c.ctx().waitTicks(20);
                c.check(c.events("terminal.click").size() == b2, "control: M4 with no drop-key binding still clicked");
                c.note("control: unbound, the same M4 press sent nothing");
            }
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Inventory Theme ON (what broke the solver on his Untested Cheat instance): every terminal type is still tracked and
     *  highlighted, and with Custom GUI on the theme stands down. */
    static void invTheme(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> problems = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", true)
                    .set("inventorytheme.InventoryThemeConfig", "Enabled", true).set("inventorytheme.InventoryThemeConfig", "HypixelOnly", false);
            for (TerminalCases.Spec spec : TerminalCases.SPECS) {
                JsonObject t = TerminalCases.open(c, spec);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                String type = null;
                for (int i = 0; i < 60 && type == null; i++) {
                    c.ctx().waitTicks(1);
                    type = c.onClient(mc -> TerminalCases.currentType());
                }
                boolean themed = c.onClient(mc -> (Boolean) Mod.staticCall("inventorytheme.InventoryThemeFeature", "shouldTheme",
                        dev.testkit.compat.McCompat.screen(mc)));
                if (!spec.type().equals(type)) {
                    problems.add(spec.type() + " not tracked");
                }
                if (themed) {
                    problems.add(spec.type() + ": Inventory Theme still themes the terminal under Custom GUI");
                }
                c.note(spec.type() + " with Inventory Theme on: tracked=" + type + " themed=" + themed);
                MenuKit.reset(c);
            }
            // Control: a plain chest still gets the theme.
            c.onClient(mc -> {
                dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.ContainerScreen(
                        net.minecraft.world.inventory.ChestMenu.threeRows(0, mc.player.getInventory()), mc.player.getInventory(),
                        net.minecraft.network.chat.Component.literal("Chest")));
                return null;
            });
            c.ctx().waitTicks(3);
            boolean themedChest = c.onClient(mc -> (Boolean) Mod.staticCall("inventorytheme.InventoryThemeFeature", "shouldTheme",
                    dev.testkit.compat.McCompat.screen(mc)));
            c.check(themedChest, "control: a plain chest is not themed with Inventory Theme on");
            c.note("control: a plain chest is themed=" + themedChest);
        } finally {
            MenuKit.reset(c);
        }
        c.check(problems.isEmpty(), String.join("; ", problems));
    }

    static void instCfg(Session c) throws Exception {
        MenuKit.reset(c);
        String loaded = c.onClient(mc -> "enabled=" + Mod.get(CONFIG, "isEnabled") + " customGui=" + Mod.get(CONFIG, "isCustomGuiEnabled")
                + " scale=" + Mod.get(CONFIG, "getScale") + " auto=" + Mod.get(CONFIG, "isAutoTerminalsEnabled")
                + " skyblockGate=" + Mod.staticCall("util.SkyblockGate", "allows"));
        c.note("seeded solver config: " + loaded);
        if (!loaded.startsWith("enabled=true")) {
            // The default config has the solver off: this case is only meaningful with -PseedConfig of an instance's config.
            c.note("no seeded config with the Terminal Solver on (run with -PseedConfig=<instance config dir>); nothing to check");
            return;
        }
        List<String> problems = new ArrayList<>();
        for (TerminalCases.Spec spec : TerminalCases.SPECS) {
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            String type = null;
            for (int i = 0; i < 60 && type == null; i++) {
                c.ctx().waitTicks(1);
                type = c.onClient(mc -> TerminalCases.currentType());
            }
            boolean melody = spec.type().equals("MELODY");
            int hl = 0;
            for (int i = 0; i < 60 && !melody && hl == 0; i++) {
                c.ctx().waitTicks(1);
                hl = c.onClient(mc -> TerminalCases.highlights().size());
            }
            Object bounds = c.onClient(mc -> Mod.field("terminals.TerminalSolverFeature", "lastGoodBounds"));
            String name = c.name() + "-" + spec.type().toLowerCase();
            java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(name, shot);
            c.note(spec.type() + ": tracked=" + type + " highlights=" + hl + " panelBounds=" + (bounds != null));
            if (!spec.type().equals(type)) {
                problems.add(spec.type() + " not tracked (currentType " + type + ")");
            } else if (!melody && hl == 0) {
                problems.add(spec.type() + " tracked but nothing highlighted");
            }
            MenuKit.reset(c);
        }
        c.check(problems.isEmpty(), "with the seeded config: " + problems);
    }
}
