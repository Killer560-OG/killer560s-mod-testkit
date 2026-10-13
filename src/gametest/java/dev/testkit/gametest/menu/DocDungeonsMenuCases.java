package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's 2026-10-13 dungeon to-do list (mod branch doc-dungeons), the halves that send interactions, on the Hx
 * session's GrimAC server.
 *
 * <pre>
 * 982 Auto E-Table stops on any key, click or scroll and on another screen opening, never on a mouse move or on the
 *     Start ETable click that began it (a real click), names what stopped it, and sends nothing after
 * 983 Fast/Auto Leap: a class target goes to the teammate Leap Order's class pick gave it, a name target to that
 *     name; Fast alone fires on his left click and not on its trigger, Auto alone on its trigger and not on the click
 * 984 Storage Scan All: every wait 1.1-1.4x the old one (mean 1.25), varied, measured on the wall clock
 * </pre>
 */
final class DocDungeonsMenuCases {

    private static final String EXP = "experiments.ExperimentsFeature";
    private static final String EXPC = "experiments.ExperimentsConfig";
    private static final String FLF = "fastleap.FastLeapFeature";
    private static final String FLC = "fastleap.FastLeapConfig";
    private static final String SCAN_ALL = "storagesearch.StorageScanAll";

    private DocDungeonsMenuCases() {
    }

    static void register(Session s) {
        // ---- doc-dungeons, killer560's 2026-10-13 list (982-984) ----
        MenuSuite.test(s, "982-menu-etable-stop-on-input", DocDungeonsMenuCases::etableStop);
        MenuSuite.test(s, "983-menu-fastleap-modes-targets", DocDungeonsMenuCases::leapModes);
        MenuSuite.test(s, "984-menu-scanall-pace", DocDungeonsMenuCases::scanAllPace);
    }

    // =================================================================================================================
    // 982 Auto E-Table: any input of his stops it
    // =================================================================================================================

    static void etableStop(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto E-Table is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(EXPC, "Enabled", true).set(EXPC, "AutonomousMode", true).set(EXPC, "SuperpairsEnabled", true)
                    .set(EXPC, "DelayMs", 150).set(EXPC, "FirstClickDelayMs", 300).set(EXPC, "RandomDelayMaxMs", 0)
                    .set(EXPC, "StopStrategy", Mod.enumValue("experiments.ExperimentStopStrategy", "MAX_XP"))
                    .set(EXPC, "BlockInputEnabled", true).set(EXPC, "AutoSwapGuardianPet", false);
            c.ctx().runOnClient(mc -> Mod.setField(EXP, "armed", false));
            MenuKit.show(c, MenuKit.menu("menus.experimentation-table"));
            MenuKit.awaitScreen(c, "Experimentation Table", 100);
            c.ctx().waitTicks(5);

            // ---- 1: the Start ETable button, pressed and released by the real mouse ----
            double[] at = c.onClient(mc -> {
                int[] b = (int[]) Mod.staticCall(EXP, "startButtonBounds");
                double s = mc.getWindow().getGuiScale();
                return new double[]{(b[0] + b[2] / 2.0) * s, (b[1] + b[3] / 2.0) * s};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(2);
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            boolean armed = armed(c);
            c.note(String.format(Locale.ROOT, "Start ETable pressed at %.0f,%.0f: armed %s, stop reason \"%s\"", at[0], at[1],
                    armed, reason(c)));
            c.check(armed, "the click on Start ETable did not start the run, or its own press/release stopped it ("
                    + reason(c) + ")");

            // ---- 2: moving the mouse is not input ----
            for (int i = 0; i < 12; i++) {
                c.ctx().getInput().setCursorPos(40 + i * 37, 30 + i * 23);
                c.ctx().waitTicks(1);
            }
            c.waitUntil("the run to click a game", mc -> !c.events("container.click").isEmpty(), 600);
            boolean stillArmed = armed(c);
            c.note("after 12 mouse moves: armed " + stillArmed + ", " + c.events("container.click").size()
                    + " game click(s) so far");
            c.check(stillArmed, "moving the mouse stopped the run (" + reason(c) + ")");

            // ---- 3: a key ----
            stopsOn(c, "the J key", () -> c.ctx().getInput().pressKey(74)); // GLFW_KEY_J
            // ---- 4: a left click on no slot ----
            rearm(c);
            c.ctx().getInput().setCursorPos(4, 4);
            c.ctx().waitTicks(2);
            stopsOn(c, "left click", () -> c.ctx().getInput().pressMouse(0));
            // ---- 5: the scroll wheel ----
            rearm(c);
            stopsOn(c, "scroll wheel", () -> c.ctx().getInput().scroll(1.0));
            // ---- 6: another screen opening ----
            rearm(c);
            stopsOn(c, "opening", () -> c.ctx().runOnClient(mc ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false))));
            // ---- 7: keys typed into a non-container screen are not a stop (that screen opening was) ----
            c.ctx().runOnClient(mc -> Mod.setField(EXP, "armed", true));
            c.ctx().getInput().pressKey(75); // GLFW_KEY_K, typed into chat
            c.ctx().waitTicks(3);
            boolean chatKey = armed(c);
            c.note("armed with chat open, K typed: armed " + chatKey);
            c.check(chatKey, "a key typed into chat stopped the run");
            c.ctx().runOnClient(mc -> {
                Mod.setField(EXP, "armed", false);
                McCompat.setScreen(mc, null);
            });
        } finally {
            c.ctx().runOnClient(mc -> Mod.setField(EXP, "armed", false));
            MenuKit.reset(c);
        }
    }

    private static boolean armed(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(EXP, "isArmed"));
    }

    private static String reason(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(EXP, "lastStopReason"));
    }

    /** Arms the run again with the table (or whichever game menu the run reached) still open. */
    private static void rearm(Session c) {
        c.ctx().runOnClient(mc -> {
            Mod.staticCall(EXP, "stopOnPlayerInput", "testkit re-arm"); // no-op unless armed
            Mod.setField(EXP, "armed", true);
        });
        c.ctx().waitTicks(2);
    }

    private static void stopsOn(Session c, String want, Runnable input) {
        String title = c.onClient(MenuKit::screenTitle);
        input.run();
        c.ctx().waitTicks(3);
        boolean armed = armed(c);
        String why = reason(c);
        int clicksAt = c.events("container.click").size();
        c.ctx().waitTicks(40);
        int after = c.events("container.click").size() - clicksAt;
        String titleAfter = c.onClient(MenuKit::screenTitle);
        c.note(String.format(Locale.ROOT, "%s: armed %s, stopped by \"%s\", %d click(s) in the 40 ticks after, screen %s -> %s",
                want, armed, why, after, title, titleAfter));
        c.check(!armed, want + " did not stop the run");
        c.check(why.contains(want), "stopped by \"" + why + "\", want it to name " + want);
        c.check(after == 0, after + " game click(s) were sent after " + want + " stopped the run");
    }

    // =================================================================================================================
    // 983 Fast/Auto Leap: targets and modes
    // =================================================================================================================

    static void leapModes(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Fast/Auto Leap is cheat-only (233 checks its getter reads false)");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-fastleap.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        Object cfgObj = c.onClient(mc -> Mod.staticCall(FLC, "getInstance"));
        Object p1 = Mod.enumValue(FLC + "$LeapGroup", "P1");
        Object door = Mod.enumValue(FLC + "$LeapGroup", "DOOR");
        Object tP1 = Mod.enumValue(FLC + "$LeapTarget", "P1");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            DungeonMenuCases.inDungeon(c);
            c.hx().tab(List.of("§r[42] §r§aAlice §r§f(§r§dMage L§r§f)", "§r[40] §r§aBob §r§f(§r§dHealer XL§r§f)",
                    "§r[39] §r§aCarol §r§f(§r§dArcher XX§r§f)"), null, null);
            c.waitUntil("Teammates to read Alice, Bob and Carol off the tab list", mc ->
                    Mod.staticCall("fastleap.Teammates", "byName", "Bob") != null
                            && Mod.staticCall("fastleap.Teammates", "byName", "Carol") != null, 100);
            giveLeap(c);
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", MenuKit.menu("menus.spirit-leap"));
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.call(cfgObj, "setTargetMode", Mod.enumValue(FLC + "$TargetMode", "PER_LEAP"));
                Mod.call(cfgObj, "setLeapFast", p1, true);
                Mod.call(cfgObj, "setLeapAuto", p1, false);
                // Leap Order's class pick: Bob, a Healer on the tab list, is playing Tank
                Mod.staticCall("dungeonclass.ClassOverrides", "set", "Bob", Mod.enumValue("dungeonclass.DungeonClass", "TANK"));
                Mod.call(cfgObj, "setTargetPickClass", tP1, Mod.enumValue("dungeonclass.DungeonClass", "TANK"));
            });

            // ---- a class target with a Leap Order class pick -> Bob's head (13) ----
            int slot = leapAndClick(c, "Tank (Bob by class pick)", () -> c.ctx().runOnClient(mc ->
                    Mod.staticCall(FLF, "leapToConfigured", tP1)));
            String sent = c.onClient(mc -> (String) Mod.staticCall(FLF, "lastTarget"));
            c.check(slot == 13 && "class TANK".equals(sent), "a Tank target leapt to slot " + slot + " (" + sent
                    + "); Bob (Healer on the tab, Tank by Leap Order's pick) is 13");
            // ---- a name target -> Carol (15) ----
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setTargetPickName", tP1, "Carol"));
            slot = leapAndClick(c, "Carol by name", () -> c.ctx().runOnClient(mc ->
                    Mod.staticCall(FLF, "leapToConfigured", tP1)));
            c.check(slot == 15, "a name target Carol leapt to slot " + slot + ", Carol is 15");

            // ---- Door Opener Fast on, Auto off: the door opening does not leap, his left click does ----
            c.ctx().runOnClient(mc -> {
                Mod.call(cfgObj, "setLeapFast", door, true);
                Mod.call(cfgObj, "setLeapAuto", door, false);
            });
            int none = noLeapAfter(c, "Fast: Carol opens a door", () -> c.hx().chat("Carol opened a WITHER door!"));
            c.check(none == 0, "a Fast Door Opener leap fired on its trigger (" + none + " leap click(s))");
            slot = leapAndClick(c, "Fast: his left click", () -> c.ctx().getInput().pressMouse(0));
            c.check(slot == 15, "his left click with the Fast Door Opener leapt to slot " + slot + ", Carol (the opener) is 15");
            // ---- Door Opener Auto on, Fast off: the door opening leaps, his left click does not ----
            c.ctx().runOnClient(mc -> {
                Mod.call(cfgObj, "setLeapFast", door, false);
                Mod.call(cfgObj, "setLeapAuto", door, true);
            });
            slot = leapAndClick(c, "Auto: Alice opens a door", () -> c.hx().chat("Alice opened a WITHER door!"));
            c.check(slot == 11, "an Auto Door Opener leap on Alice's door went to slot " + slot + ", Alice is 11");
            none = noLeapAfter(c, "Auto: his left click", () -> c.ctx().getInput().pressMouse(0));
            c.check(none == 0, "his left click leapt with the Door Opener leap in Auto mode (" + none + " click(s))");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(FLC, "load");
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", "Bob");
            });
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            c.hx().call("give", "slot", 0, "stack", "minecraft:air");
            c.hx().tabClear();
            DungeonMenuCases.leaveDungeon(c);
            MenuKit.reset(c);
        }
    }

    /** A Spirit Leap in hotbar slot 0, held (the test server spends the item on each use). */
    private static void giveLeap(Session c) {
        c.hx().give(0, MenuKit.item("items.spirit-leap"));
        c.waitUntil("the leap in hotbar slot 0", mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
        c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
    }

    /** Clears the leap cooldown, runs {@code trigger}, and returns the slot the leap clicked in the Spirit Leap menu. */
    private static int leapAndClick(Session c, String what, Runnable trigger) {
        giveLeap(c);
        c.ctx().runOnClient(mc -> Mod.setField("fastleap.LeapManager", "leapCdTicks", 0.0));
        int before = c.events("menu.action").size();
        trigger.run();
        c.waitUntil(what + ": a leap click", mc -> c.events("menu.action").size() > before, 200);
        List<JsonObject> actions = c.events("menu.action");
        int slot = actions.get(actions.size() - 1).get("slot").getAsInt();
        MenuKit.awaitNoScreen(c, 60);
        c.waitUntil("LeapManager idle", mc -> !(Boolean) Mod.staticCall("fastleap.LeapManager", "isBusy"), 100);
        c.note(what + ": clicked slot " + slot);
        return slot;
    }

    /** Clears the cooldown, runs {@code trigger}, and returns how many leap clicks followed within 40 ticks. */
    private static int noLeapAfter(Session c, String what, Runnable trigger) {
        giveLeap(c);
        c.ctx().runOnClient(mc -> Mod.setField("fastleap.LeapManager", "leapCdTicks", 0.0));
        int before = c.events("menu.action").size();
        int usesBefore = c.events("use_item").size();
        trigger.run();
        c.ctx().waitTicks(40);
        int n = c.events("menu.action").size() - before;
        int uses = c.events("use_item").size() - usesBefore;
        c.note(what + ": " + n + " leap click(s), " + uses + " use(s) of the leap in 40 ticks");
        return n + uses;
    }

    // =================================================================================================================
    // 984 Storage Scan All: slower, varied
    // =================================================================================================================

    static void scanAllPace(Session c) throws Exception {
        MenuKit.reset(c);
        JsonObject overview = MenuKit.menu("menus.storage-overview-locked-empty");
        JsonObject ec = MenuKit.menu("menus.ender-chest-1");
        JsonObject bp = MenuKit.menu("menus.storage-backpack-page");
        try {
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"storage\",\"menu\":" + overview + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"enderchest\",\"menu\":" + ec + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"backpack\",\"menu\":" + bp + "}"));
            c.ctx().waitTicks(5);
            c.ctx().runOnClient(mc -> Mod.staticCall(SCAN_ALL, "start", true));
            c.waitUntil("Scan All to start", mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            c.waitUntil("Scan All to finish", mc -> !(Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 2400);
            @SuppressWarnings("unchecked")
            List<String> log = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(SCAN_ALL, "actionLog")));
            List<Integer> settles = new ArrayList<>();
            List<Integer> waits = new ArrayList<>();
            List<Long> openToClose = new ArrayList<>();
            List<Long> closeToCommand = new ArrayList<>();
            long openAt = -1;
            long closeAt = -1;
            for (String line : log) {
                String[] w = line.split(" ");
                long ms = Long.parseLong(w[0]);
                if (w[1].equals("open")) {
                    settles.add(Integer.parseInt(w[2].substring("settle=".length())));
                    openAt = ms;
                } else if (w[1].equals("close")) {
                    waits.add(Integer.parseInt(w[w.length - 1].substring("wait=".length())));
                    if (openAt >= 0) {
                        openToClose.add(ms - openAt);
                    }
                    closeAt = ms;
                } else if (w[1].equals("command") && closeAt >= 0) {
                    closeToCommand.add(ms - closeAt);
                }
            }
            // whole page cycles (one command to the next) against what the old pacing gives on the same server:
            // the measured command->open latency + the old 12-tick settle + the old 1-tick gap
            List<Long> cycles = new ArrayList<>();
            List<Long> latency = new ArrayList<>();
            long lastCommand = -1;
            for (String line : log) {
                String[] w = line.split(" ");
                long ms = Long.parseLong(w[0]);
                if (w[1].equals("command")) {
                    if (lastCommand >= 0) {
                        cycles.add(ms - lastCommand);
                    }
                    lastCommand = ms;
                } else if (w[1].equals("open") && lastCommand >= 0) {
                    latency.add(ms - lastCommand);
                }
            }
            double meanCycle = cycles.stream().mapToLong(Long::longValue).average().orElse(0);
            double oldCycle = latency.stream().mapToLong(Long::longValue).average().orElse(0) + 12 * 50 + 50;
            double cycleSd = Math.sqrt(cycles.stream().mapToDouble(x -> (x - meanCycle) * (x - meanCycle)).average().orElse(0));
            c.note(String.format(Locale.ROOT, "page cycles ms %s: mean %.0f, sd %.0f; the old pacing on this server: %.0f "
                    + "(latency %s + 650) -> %.2fx", cycles, meanCycle, cycleSd, oldCycle, latency, meanCycle / oldCycle));
            c.check(meanCycle / oldCycle >= 1.15 && meanCycle / oldCycle <= 1.35, String.format(Locale.ROOT,
                    "a page cycle is %.2fx the old pacing, want 1.2-1.3 (within sampling, 1.15-1.35)", meanCycle / oldCycle));
            c.check(cycleSd > 20, "page cycles hardly vary (sd " + Math.round(cycleSd) + " ms) - a fixed rhythm");
            double meanSettle = settles.stream().mapToInt(Integer::intValue).average().orElse(0);
            long distinct = settles.stream().distinct().count();
            double meanOpen = openToClose.stream().mapToLong(Long::longValue).average().orElse(0);
            double meanGap = closeToCommand.stream().mapToLong(Long::longValue).average().orElse(0);
            c.note("Scan All actions: " + log.size() + "; settle waits (ticks) " + settles + " mean "
                    + String.format(Locale.ROOT, "%.2f = %.2fx the old 12", meanSettle, meanSettle / 12.0)
                    + "; waits before the next page " + waits + "; open->close ms " + openToClose + " (mean "
                    + String.format(Locale.ROOT, "%.0f, old 600", meanOpen) + "); close->next command ms "
                    + closeToCommand + String.format(Locale.ROOT, " (mean %.0f, old about 50)", meanGap));
            c.check(settles.size() >= 6, "only " + settles.size() + " page(s) were opened - the scan did not run");
            c.check(settles.stream().allMatch(s -> s >= 13 && s <= 17), "a settle wait outside 13-17 ticks: " + settles);
            c.check(waits.stream().allMatch(s -> s >= 0 && s <= 1), "a wait before the next page outside 0-1 ticks: " + waits);
            c.check(distinct >= 2, "every settle wait was the same (" + settles + ") - a fixed rhythm");
            c.check(meanSettle / 12.0 >= 1.1 && meanSettle / 12.0 <= 1.4, "settle waits average "
                    + String.format(Locale.ROOT, "%.2fx", meanSettle / 12.0));
            for (int i = 0; i < Math.min(settles.size(), openToClose.size()); i++) {
                c.check(openToClose.get(i) >= settles.get(i) * 50L - 60, "page " + (i + 1) + " closed after "
                        + openToClose.get(i) + " ms, its wait was " + settles.get(i) + " ticks");
            }
            c.check(c.events("container.click", "menu.click").isEmpty(), "Scan All clicked a slot in a storage menu");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(SCAN_ALL, "stop", "testkit case ended");
                if (mc.player != null && McCompat.screen(mc) instanceof AbstractContainerScreen<?>) {
                    mc.player.closeContainer();
                }
            });
            for (String name : new String[]{"storage", "enderchest", "backpack"}) {
                c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"" + name + "\",\"menu\":null}"));
            }
            MenuKit.reset(c);
        }
    }
}
