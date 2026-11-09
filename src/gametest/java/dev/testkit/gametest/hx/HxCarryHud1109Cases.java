package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch carryhud-1109: Carry Tracker HUD, adding clients (Add Player path, Add Party, /carry), per-client runs target, the
 * one stop alert, Remove When Done and persistence. Each case asserts the feature acted (a row drawn, a count moved, an alert counted).
 */
final class HxCarryHud1109Cases {

    private static final String CT = "carrytracker.CarryTracker";
    private static final String CMDS = "carrytracker.CarryCommands";
    private static final String CFG = "carrytracker.CarryTrackerConfig";
    private static final String HUD = "carrytracker.CarryHud";
    private static final String SEEN = "hud.HudSeen";

    private HxCarryHud1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-carryhud-add-box", HxCarryHud1109Cases::addBox);
        s.test("1109-carryhud-add-party", HxCarryHud1109Cases::addParty);
        s.test("1109-carryhud-command", HxCarryHud1109Cases::command);
        s.test("1109-carryhud-hud", HxCarryHud1109Cases::hud);
        s.test("1109-carryhud-alert", HxCarryHud1109Cases::alert);
        s.test("1109-carryhud-remove-when-done", HxCarryHud1109Cases::removeWhenDone);
        s.test("1109-carryhud-persist", HxCarryHud1109Cases::persist);
    }

    private static boolean tracked(Session c, String n) {
        return (Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "isTracked", n));
    }

    private static int runs(Session c, String n) {
        return ((Number) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "getRuns", n))).intValue();
    }

    private static long count(Session c, String n) {
        return ((Number) c.onClient(mc -> Mod.staticCall(CT, "count", n))).longValue();
    }

    private static int alerts(Session c) {
        return ((Number) c.onClient(mc -> Mod.staticCall(CT, "alertsForTest"))).intValue();
    }

    private static void drain(Session c) {
        run(c, () -> Mod.staticCall(CT, "drainForTest"));
    }

    private static boolean add(Session c, String n, int runs) {
        boolean ok = (Boolean) c.onClient(mc -> Mod.staticCall(CMDS, "addClient", n, runs));
        drain(c);
        return ok;
    }

    private static void clear(Session c, String... names) {
        for (String n : names) {
            run(c, () -> {
                Mod.call(Mod.cfg(CFG), "remove", n);
                Mod.staticCall(CT, "reset", n);
            });
        }
        run(c, () -> Mod.call(Mod.cfg(CFG), "save"));
        drain(c);
    }

    private static void record(Session c, String... party) {
        run(c, () -> Mod.staticCall(CT, "recordForTest", "F7", 600_000L, 300, List.of(party)));
        drain(c);
        c.ctx().waitTicks(3);
    }

    private static boolean command(Session c, String line) {
        run(c, () -> net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand(line));
        c.ctx().waitTicks(3);
        drain(c);
        return true;
    }

    private static void addBox(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            c.check(add(c, "HxBoxA", 0), "the Add Player path refused a valid name");
            int def = ((Number) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "getDefaultRuns"))).intValue();
            c.check(tracked(c, "HxBoxA") && runs(c, "HxBoxA") == def, "HxBoxA runs " + runs(c, "HxBoxA") + " default " + def);
            c.check(add(c, "HxBoxB", 9) && runs(c, "HxBoxB") == 9, "an explicit runs count was not kept");
            c.check(!add(c, "HxBoxA", 3), "a duplicate name was added");
            c.check(!add(c, "bad name!", 3), "an invalid name was added");
            c.note("added with default " + def + " and explicit 9");
            clear(c, "HxBoxA", "HxBoxB");
        }
    }

    private static void addParty(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            dungeon(c);
            int n = ((Number) c.onClient(mc -> Mod.staticCall(CMDS, "addParty"))).intValue();
            drain(c);
            c.check(tracked(c, "HxMateA") && tracked(c, "HxMateB"), "Add Party added " + n + "; mates not on the list");
            c.check(!tracked(c, self(c)), "Add Party added the player himself");
            int again = ((Number) c.onClient(mc -> Mod.staticCall(CMDS, "addParty"))).intValue();
            c.check(again == 0, "a second Add Party added " + again);
            c.note("Add Party added " + n);
            clear(c, "HxMateA", "HxMateB");
        }
    }

    private static void command(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            c.check(command(c, "carry add HxCmdA 3") && tracked(c, "HxCmdA") && runs(c, "HxCmdA") == 3, "/carry add HxCmdA 3: runs " + runs(c, "HxCmdA"));
            c.check(command(c, "carry add HxCmdB") && tracked(c, "HxCmdB"), "/carry add without runs");
            c.check(command(c, "carry list"), "/carry list did not run");
            c.check(command(c, "carry remove HxCmdA") && !tracked(c, "HxCmdA"), "/carry remove left HxCmdA");
            clear(c, "HxCmdA", "HxCmdB");
        }
    }

    private static void hud(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(SEEN, "forget", "carry_tracker"));
            c.ctx().waitTicks(5);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(SEEN, "drawnRecently", "carry_tracker")), "the HUD drew with no clients");
            add(c, "HxMateA", 2);
            record(c, "HxMateA");
            c.waitUntil("a Carry Tracker row for HxMateA", mc -> ((List<?>) Mod.staticCall(HUD, "lastLinesForTest")).contains("HxMateA  1/2 runs"), 100);
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(SEEN, "drawnRecently", "carry_tracker")), "HudSeen never saw the HUD");
            c.note("rows " + c.onClient(mc -> Mod.staticCall(HUD, "lastLinesForTest")));
            clear(c, "HxMateA");
        }
    }

    private static void alert(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            add(c, "HxAlertA", 2);
            int before = alerts(c);
            record(c, "HxAlertA");
            c.check(alerts(c) == before, "alerted at 1/2");
            record(c, "HxAlertA");
            c.waitUntil("one alert at 2/2", mc -> ((Number) Mod.staticCall(CT, "alertsForTest")).intValue() == before + 1, 60);
            String line = c.onClient(mc -> (String) Mod.staticCall(CT, "lastAlertForTest"));
            c.check(line != null && line.contains("Stop carrying HxAlertA: 2/2 runs done"), "alert line: " + line);
            record(c, "HxAlertA");
            c.check(alerts(c) == before + 1, "alerted again at 3/2: " + alerts(c));
            c.note(line);
            clear(c, "HxAlertA");
        }
    }

    private static void removeWhenDone(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            add(c, "HxDoneA", 1);
            add(c, "HxDoneB", 2);
            run(c, () -> Mod.call(Mod.cfg(CFG), "setRemoveWhenDone", true));
            record(c, "HxDoneA");
            c.waitUntil("HxDoneA removed when done", mc -> !(Boolean) Mod.call(Mod.cfg(CFG), "isTracked", "HxDoneA"), 60);
            c.check(tracked(c, "HxDoneB"), "an unfinished client was removed");
            run(c, () -> Mod.call(Mod.cfg(CFG), "setRemoveWhenDone", false));
            record(c, "HxDoneB");
            record(c, "HxDoneB");
            c.check(tracked(c, "HxDoneB") && count(c, "HxDoneB") == 2, "HxDoneB gone or miscounted with Remove When Done OFF: " + count(c, "HxDoneB"));
            // Re-adding a finished client starts at zero.
            c.check(add(c, "HxDoneA", 1) && count(c, "HxDoneA") == 0, "re-added HxDoneA count " + count(c, "HxDoneA"));
            clear(c, "HxDoneA", "HxDoneB");
        }
    }

    private static void persist(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            add(c, "HxKeepA", 7);
            run(c, () -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setDefaultRuns", 11);
                Mod.call(cfg, "setRemoveWhenDone", true);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            c.check(tracked(c, "HxKeepA") && runs(c, "HxKeepA") == 7, "after a reload HxKeepA runs " + runs(c, "HxKeepA"));
            c.check(((Number) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "getDefaultRuns"))).intValue() == 11, "default runs lost");
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg(CFG), "isRemoveWhenDone")), "Remove When Done lost");
            run(c, () -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setDefaultRuns", 5);
                Mod.call(cfg, "setRemoveWhenDone", false);
                Mod.call(cfg, "setEnabled", false);
            });
            clear(c, "HxKeepA");
        }
    }
}
