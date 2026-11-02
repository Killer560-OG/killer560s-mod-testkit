package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * killer560's 2026-11-01 to-do Doc item "ETABLE" (mod branch doc-etable-1101), on the Hx session's GrimAC server.
 *
 * <pre>
 * 1101-menu-etable-resume      Auto E-Table on Ultrasequencer, stopped by Escape mid-round with a click decided: the
 *                              table stays open, the solver's next step is the board's real next note, and finishing the
 *                              round by hand (real mouse clicks) moves the solver on click by click - the round is
 *                              accepted with no wrong click and the next round's notes are learned
 * 1101-menu-superpairs-hud     the Superpairs Overview on a known board: found rewards (claimed / 1 of 2), the powerup
 *                              turned over, undiscovered pairs and powerups, drawn over the real board
 * 1101-menu-macro-stop-rules   macrostop/MacroStop through the real key and mouse handlers: a click macro stops only on
 *                              Escape or the Emergency Cancel Key; a movement macro also on W and the chat key with no
 *                              screen open; J and a left click stop neither; nothing typed into chat stops either
 * 1101-menu-ecancel-terminal  Auto Terminals mid-terminal: the Emergency Cancel Key holds it (no click for 40 ticks,
 *                              the terminal not solved); the key again releases it and it finishes the terminal
 * </pre>
 */
final class DocEtable1101Cases {

    private static final String EXP = "experiments.ExperimentsFeature";
    private static final String EXPC = "experiments.ExperimentsConfig";
    private static final String MSC = "macrostop.MacroStopConfig";

    private DocEtable1101Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-menu-etable-resume", DocEtable1101Cases::resume);
        MenuSuite.test(s, "1101-menu-superpairs-hud", DocEtable1101Cases::superpairsHud);
        MenuSuite.test(s, "1101-menu-macro-stop-rules", DocEtable1101Cases::stopRules);
        MenuSuite.test(s, "1101-menu-ecancel-terminal", DocEtable1101Cases::terminalCancel);
    }

    // =================================================================================================================
    // 1101-menu-etable-resume
    // =================================================================================================================

    static void resume(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto E-Table is cheat-only (221 covers the solver following his clicks)");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            // A long Click Delay so a decided click is usually still in flight when Escape lands (the rewind path).
            cfg.set(EXPC, "Enabled", true).set(EXPC, "AutonomousMode", true).set(EXPC, "DelayMs", 1000)
                    .set(EXPC, "FirstClickDelayMs", 300).set(EXPC, "RandomDelayMaxMs", 0)
                    .set(EXPC, "StopStrategy", Mod.enumValue("experiments.ExperimentStopStrategy", "MAX_XP"))
                    .set(EXPC, "BlockInputEnabled", false).set(EXPC, "AutoSwapGuardianPet", false)
                    .set(EXPC, "ClickProtectionEnabled", true);
            c.ctx().runOnClient(mc -> Mod.setField(EXP, "armed", true));
            JsonObject st = c.hx().call("menu.experiment", "game", "ULTRASEQUENCER", "seed", 57, "rounds", 4, "watch", 8,
                    "hold", 6).getAsJsonObject();
            String title = st.get("title").getAsString();
            MenuKit.awaitScreen(c, title, 100);
            // Round 3 has four notes: let the bot click the first one of them, then stop it.
            try {
                c.waitUntil("round 3's turn", mc -> {
                    JsonObject now = ExperimentCases.state(c);
                    return now.get("round").getAsInt() == 3 && now.get("phase").getAsString().equals("TURN");
                }, 900);
            } catch (AssertionError e) {
                c.note("no round 3: server " + ExperimentCases.state(c) + ", " + c.events("container.click").size()
                        + " container click(s), armed " + c.onClient(mc -> Mod.staticCall(EXP, "isArmed"))
                        + ", stop reason \"" + c.onClient(mc -> Mod.staticCall(EXP, "lastStopReason")) + "\", screen "
                        + c.onClient(MenuKit::screenTitle));
                throw e;
            }
            c.waitUntil("the bot's first click of round 3", mc -> roundClicks(c, 3) >= 1, 200);
            c.ctx().waitTicks(4); // the next click is decided now and waits out the 1000 ms Click Delay
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(256)); // GLFW_KEY_ESCAPE
            c.ctx().waitTicks(3);
            boolean armed = c.onClient(mc -> (Boolean) Mod.staticCall(EXP, "isArmed"));
            String why = c.onClient(mc -> (String) Mod.staticCall(EXP, "lastStopReason"));
            String titleAfter = c.onClient(MenuKit::screenTitle);
            c.check(!armed && why.contains("Escape"), "Escape did not stop the run: armed " + armed + ", \"" + why + "\"");
            c.check(title.equals(titleAfter), "Escape changed the screen " + title + " -> " + titleAfter);
            c.ctx().waitTicks(30); // a stopped run sends nothing: let any stray decided click show itself
            List<Integer> seq = ExperimentCases.seq(ExperimentCases.state(c));
            int accepted = roundClicks(c, 3);
            int solverNext = c.onClient(mc -> (Integer) Mod.staticCall(EXP, "solverSequenceIndex"));
            c.note(String.format(Locale.ROOT, "stopped by \"%s\" with %d of %d notes accepted %s; solver's next step %d",
                    why, accepted, seq.size(), seq, solverNext));
            c.check(accepted >= 1 && accepted < seq.size(), "the stop did not land mid-round (" + accepted + "/" + seq.size() + ")");
            c.check(solverNext == accepted, "after the stop the solver expects step " + solverNext + " but the board is at "
                    + accepted);

            // Finish the round by hand, with the real mouse.
            int followed0 = c.onClient(mc -> (Integer) Mod.staticCall(EXP, "manualClicksFollowed"));
            List<String> steps = new ArrayList<>();
            for (int i = accepted; i < seq.size(); i++) {
                int slot = seq.get(i);
                int idxBefore = c.onClient(mc -> (Integer) Mod.staticCall(EXP, "solverSequenceIndex"));
                realClick(c, slot);
                c.ctx().waitTicks(4);
                int idxAfter = c.onClient(mc -> (Integer) Mod.staticCall(EXP, "solverSequenceIndex"));
                steps.add(slot + ":" + idxBefore + "->" + idxAfter);
                if (i < seq.size() - 1) {
                    c.check(idxAfter == idxBefore + 1, "his click on slot " + slot + " did not move the solver on ("
                            + idxBefore + " -> " + idxAfter + ")");
                }
            }
            int followed = c.onClient(mc -> (Integer) Mod.staticCall(EXP, "manualClicksFollowed")) - followed0;
            c.waitUntil("round 4 to start", mc -> ExperimentCases.state(c).get("round").getAsInt() == 4, 200);
            JsonObject end = ExperimentCases.state(c);
            c.waitUntil("round 4's notes to be learned", mc -> {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) Mod.field(ExperimentCases.solverObj(), "ultrasequencer");
                return m.size() == 5;
            }, 200);
            c.note("by hand: " + steps + "; solver followed " + followed + " click(s); server round " + end.get("round")
                    + ", wrong " + end.get("wrong"));
            c.check(followed == seq.size() - accepted, "the solver followed " + followed + " of his "
                    + (seq.size() - accepted) + " clicks");
            c.check(end.get("wrong").getAsInt() == 0, "the server counted " + end.get("wrong") + " wrong click(s)");
        } finally {
            c.ctx().runOnClient(mc -> Mod.setField(EXP, "armed", false));
            MenuKit.reset(c);
        }
    }

    /** Accepted (correct) clicks the server counted in {@code round}. */
    private static int roundClicks(Session c, int round) {
        int n = 0;
        for (JsonObject e : c.events("experiment.click")) {
            if (e.get("round").getAsInt() == round && e.has("correct") && e.get("correct").getAsBoolean()) {
                n++;
            }
        }
        return n;
    }

    /** A real mouse press on a slot of the open container. */
    private static void realClick(Session c, int slot) {
        double[] at = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(slot);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(left + sl.x + 8) * scale, (top + sl.y + 8) * scale};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
    }

    // =================================================================================================================
    // 1101-menu-ecancel-terminal
    // =================================================================================================================

    static void terminalCancel(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(TerminalCases.CONFIG, "Enabled", true).set(TerminalCases.CONFIG, "AutoTerminalsEnabled", true)
                    .set(TerminalCases.CONFIG, "AutoNumbersEnabled", true);
            c.ctx().runOnClient(mc -> Mod.set(MSC, "setEmergencyCancelKeyCode", 75)); // K
            int clicks0 = c.events("terminal.click").size();
            JsonObject t = TerminalCases.open(c, TerminalCases.NUMBERS_10);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("Auto Terminals' first two clicks", mc -> c.events("terminal.click").size() - clicks0 >= 2, 200);
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(75));
            c.ctx().waitTicks(2);
            int atKey = c.events("terminal.click").size() - clicks0;
            boolean held = c.onClient(mc -> (Boolean) Mod.staticCall("macrostop.MacroStop", "emergencyHeld"));
            c.ctx().waitTicks(40);
            int during = c.events("terminal.click").size() - clicks0 - atKey;
            boolean solvedDuring = !c.events("terminal.solved").isEmpty();
            String title = c.onClient(MenuKit::screenTitle);
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(75)); // released
            c.ctx().waitTicks(2);
            boolean heldAfter = c.onClient(mc -> (Boolean) Mod.staticCall("macrostop.MacroStop", "emergencyHeld"));
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 400);
            c.note("K after " + atKey + " click(s): hold " + held + ", " + during + " click(s) in the 40 ticks after, solved "
                    + solvedDuring + ", screen " + title + "; K again: hold " + heldAfter + ", then solved with "
                    + solved.get("clicks") + " clicks, " + solved.get("wrong") + " wrong");
            c.check(held, "the Emergency Cancel Key did not start the hold");
            c.check(during == 0 && !solvedDuring, during + " Auto Terminals click(s) went out during the hold");
            c.check(!heldAfter, "the key pressed again did not release the hold");
            c.check(solved.get("wrong").getAsInt() == 0, "wrong clicks: " + solved);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("macrostop.MacroStop", "release", "testkit");
                Mod.set(MSC, "setEmergencyCancelKeyCode", -1);
            });
            MenuKit.reset(c);
        }
    }

    // =================================================================================================================
    // 1101-menu-superpairs-hud
    // =================================================================================================================

    static void superpairsHud(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(EXPC, "Enabled", true).set(EXPC, "AutonomousMode", false).set(EXPC, "SuperpairsEnabled", true)
                    .set(EXPC, "SuperpairsHudEnabled", true);
            // slots 9..15: Power VI, Sharpness V, XP, Power VI, Sharpness V, XP, a powerup
            JsonArray layout = new JsonArray();
            layout.add(tile("minecraft:enchanted_book", "Power VI"));
            layout.add(tile("minecraft:enchanted_book", "Sharpness V"));
            layout.add(tile("minecraft:lime_dye", "25k Enchanting Exp"));
            layout.add(tile("minecraft:enchanted_book", "Power VI"));
            layout.add(tile("minecraft:enchanted_book", "Sharpness V"));
            layout.add(tile("minecraft:lime_dye", "25k Enchanting Exp"));
            JsonObject up = new JsonObject();
            up.addProperty("powerup", true);
            layout.add(up);
            JsonObject args = new JsonObject();
            args.addProperty("game", "SUPERPAIRS");
            args.addProperty("clicks", 20);
            args.add("layout", layout);
            JsonObject st = c.hx().call("menu.experiment", args).getAsJsonObject();
            MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
            c.ctx().waitTicks(5);
            long frames0 = frames(c);
            List<String> start = lines(c);
            // Power VI + Power VI: a claimed pair. Then Sharpness V + XP: no match, both cover back up.
            for (int slot : new int[]{9, 12, 10, 11}) {
                MenuKit.click(c, slot, 0, ContainerInput.PICKUP);
                c.ctx().waitTicks(5);
            }
            c.ctx().waitTicks(15); // the server re-covers the miss after 10 ticks
            List<String> mid = lines(c);
            long framesMid = frames(c) - frames0;
            c.note("start: " + start);
            c.note("after P6+P6 and S5+XP: " + mid + " (" + framesMid + " frame(s) drawn)");
            c.check(framesMid > 0, "the Superpairs Overview drew nothing over the open board");
            c.check(has(mid, "Found: §f3"), "want 3 rewards found: " + mid);
            c.check(has(mid, "§aPower VI") || mid.stream().anyMatch(l -> l.startsWith(" §a") && l.contains("Power")),
                    "Power VI not shown as claimed: " + mid);
            c.check(mid.stream().filter(l -> l.contains("1/2")).count() == 2, "want Sharpness V and the XP at 1/2: " + mid);
            c.check(has(mid, "Undiscovered pairs: §e2") && !has(mid, "new"),
                    "want exactly 2 undiscovered pairs (both partners, no wholly unseen pair): " + mid);
            c.check(has(mid, "Undiscovered powerups: §e1"), "want exactly 1 undiscovered powerup: " + mid);
            // Turn the powerup over: it is found, none is left.
            MenuKit.click(c, 15, 0, ContainerInput.PICKUP);
            c.ctx().waitTicks(8);
            List<String> end = lines(c);
            c.note("after the powerup: " + end);
            c.check(has(end, "Powerups: §b"), "the powerup is not listed as found: " + end);
            c.check(has(end, "Undiscovered powerups: §e0"), "want 0 undiscovered powerups: " + end);
            // Switched off, it draws nothing.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(EXPC), "setSuperpairsHudEnabled", false);
                return null;
            });
            long off0 = frames(c);
            c.ctx().waitTicks(10);
            long off = frames(c) - off0;
            c.check(off == 0, "switched off, it still drew " + off + " frame(s)");
        } finally {
            MenuKit.reset(c);
        }
    }

    private static JsonObject tile(String item, String name) {
        JsonObject t = new JsonObject();
        t.addProperty("item", item);
        t.addProperty("name", name);
        return t;
    }

    private static boolean has(List<String> lines, String part) {
        return lines.stream().anyMatch(l -> l.contains(part));
    }

    private static long frames(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall("experiments.SuperpairsHud", "framesDrawn"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall("experiments.SuperpairsHud", "lastLines")));
    }

    // =================================================================================================================
    // 1101-menu-macro-stop-rules
    // =================================================================================================================

    static void stopRules(Session c) throws Exception {
        MenuKit.reset(c);
        AtomicBoolean clickRunning = new AtomicBoolean(false);
        AtomicBoolean moveRunning = new AtomicBoolean(false);
        AtomicReference<String> clickWhy = new AtomicReference<>("");
        AtomicReference<String> moveWhy = new AtomicReference<>("");
        // Registered once per client JVM; both report "not running" whenever this case is not driving them.
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("macrostop.MacroStop", "register", "testkit click macro",
                    Mod.enumValue("macrostop.MacroStop$Kind", "CLICK"),
                    (java.util.function.BooleanSupplier) clickRunning::get,
                    (java.util.function.Consumer<String>) why -> {
                        clickWhy.set(why);
                        clickRunning.set(false);
                    });
            Mod.staticCall("macrostop.MacroStop", "register", "testkit movement macro",
                    Mod.enumValue("macrostop.MacroStop$Kind", "MOVEMENT"),
                    (java.util.function.BooleanSupplier) moveRunning::get,
                    (java.util.function.Consumer<String>) why -> {
                        moveWhy.set(why);
                        moveRunning.set(false);
                    });
            Mod.set(MSC, "setEmergencyCancelKeyCode", 75); // K
        });
        List<String> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            // no screen open
            row(c, rows, problems, "J, no screen", 74, false, false, false, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "left click, no screen", -1, false, false, false, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "W, no screen", 87, false, false, true, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "T (chat), no screen", 84, false, false, true, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "K (Emergency Cancel), no screen", 75, false, true, true, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "Escape, no screen", 256, false, true, true, clickRunning, moveRunning, clickWhy, moveWhy);
            // chat open: typing is never a stop
            row(c, rows, problems, "K typed into chat", 75, true, false, false, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "W typed into chat", 87, true, false, false, clickRunning, moveRunning, clickWhy, moveWhy);
            // a container open: Escape and the Emergency Cancel Key only
            row(c, rows, problems, "W in a container", 87, null, false, false, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "K in a container", 75, null, true, true, clickRunning, moveRunning, clickWhy, moveWhy);
            row(c, rows, problems, "Escape in a container", 256, null, true, true, clickRunning, moveRunning, clickWhy, moveWhy);
        } finally {
            clickRunning.set(false);
            moveRunning.set(false);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("macrostop.MacroStop", "release", "testkit");
                Mod.set(MSC, "setEmergencyCancelKeyCode", -1);
                McCompat.setScreen(mc, null);
            });
            MenuKit.reset(c);
        }
        rows.forEach(c::note);
        c.check(problems.isEmpty(), String.join(" | ", problems));
    }

    /**
     * One press with both test macros running. {@code screen}: false = none, true = chat, null = a container.
     * {@code key} -1 is a left click.
     */
    private static void row(Session c, List<String> rows, List<String> problems, String what, int key, Boolean screen,
            boolean clickStops, boolean moveStops, AtomicBoolean clickRunning, AtomicBoolean moveRunning,
            AtomicReference<String> clickWhy, AtomicReference<String> moveWhy) {
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(2);
        if (screen == null) {
            MenuKit.show(c, MenuKit.menu("menus.experimentation-table"));
            MenuKit.awaitScreen(c, "Experimentation Table", 100);
        } else if (screen) {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false)));
        }
        c.ctx().waitTicks(3);
        clickWhy.set("");
        moveWhy.set("");
        clickRunning.set(true);
        moveRunning.set(true);
        if (key == -1) {
            c.ctx().getInput().setCursorPos(4, 4);
            c.ctx().waitTicks(1);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        } else {
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(key));
        }
        c.ctx().waitTicks(3);
        boolean clickStopped = !clickRunning.get();
        boolean moveStopped = !moveRunning.get();
        clickRunning.set(false);
        moveRunning.set(false);
        String row = String.format(Locale.ROOT, "%-34s click macro %s%s, movement macro %s%s", what,
                clickStopped ? "STOPPED" : "kept on", clickStopped ? " (\"" + clickWhy.get() + "\")" : "",
                moveStopped ? "STOPPED" : "kept on", moveStopped ? " (\"" + moveWhy.get() + "\")" : "");
        rows.add(row);
        if (clickStopped != clickStops || moveStopped != moveStops) {
            problems.add(what + ": want click " + (clickStops ? "stop" : "keep") + ", movement "
                    + (moveStops ? "stop" : "keep") + " - " + row.trim());
        }
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(2);
    }
}
