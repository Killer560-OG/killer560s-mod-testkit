package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fast/Auto Leap v2 (mod branch leap-v2, killer560 2026-10-13: "for fast leap it should be a toggle for each specific
 * section. For auto it should swap to your leap, open it, then leap to the person, then swap back to the item you
 * want"), on the Hx session's GrimAC server. The session fails a case on any anticheat line.
 *
 * <pre>
 * 1341 the Auto sequence from a sword in slot 1 with the leap in slot 5: swap to 5, use, click the target's head, swap
 *      to Return To (Previous Slot, then Slot 3), each on its own client tick and in that order on the server; then
 *      Fast and Auto both on: the trigger leaps, and so does his left click
 * 1342 the failures say which step: no leap in the hotbar (one in the inventory is never moved: nothing sent), the menu
 *      did not open (and it still swaps back), the target is not in the menu
 * </pre>
 */
final class LeapV2MenuCases {

    private static final String FLF = "fastleap.FastLeapFeature";
    private static final String FLC = "fastleap.FastLeapConfig";
    private static final String LM = "fastleap.LeapManager";

    private LeapV2MenuCases() {
    }

    static void register(Session s) {
        // ---- leap-v2, killer560's 2026-10-13 review of Fast/Auto Leap (1341-1342) ----
        MenuSuite.test(s, "1341-menu-autoleap-sequence", LeapV2MenuCases::sequence);
        MenuSuite.test(s, "1342-menu-autoleap-failures", LeapV2MenuCases::failures);
    }

    // =================================================================================================================
    // 1341 the Auto sequence, Return To, Fast + Auto together
    // =================================================================================================================

    static void sequence(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Fast/Auto Leap is cheat-only");
            return;
        }
        Path file = configFile(c);
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        Object cfgObj = c.onClient(mc -> Mod.staticCall(FLC, "getInstance"));
        Object door = Mod.enumValue(FLC + "$LeapGroup", "DOOR");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            party(c, List.of("Alice", "Bob", "Carol"));
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", MenuKit.menu("menus.spirit-leap"));
            c.ctx().runOnClient(mc -> {
                Mod.call(cfgObj, "setTargetMode", Mod.enumValue(FLC + "$TargetMode", "PER_LEAP"));
                Mod.call(cfgObj, "setLeapFast", door, false);
                Mod.call(cfgObj, "setLeapAuto", door, true);
                Mod.call(cfgObj, "setReturnToSlot", -1);
            });

            // ---- 1: Return To Previous Slot: sword in slot 1 held, leap in slot 5 ----
            int[] r = autoLeap(c, "Return To Previous Slot", "Alice", 0);
            c.check(r[0] == 11, "the Auto leap on Alice's door clicked slot " + r[0] + ", Alice is 11");
            c.check(r[1] == 0, "after the leap he holds slot " + (r[1] + 1) + ", Return To Previous is slot 1");

            // ---- 2: Return To Slot 3 ----
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setReturnToSlot", 2));
            r = autoLeap(c, "Return To Slot 3", "Bob", 2);
            c.check(r[0] == 13, "the Auto leap on Bob's door clicked slot " + r[0] + ", Bob is 13");
            c.check(r[1] == 2, "after the leap he holds slot " + (r[1] + 1) + ", Return To is slot 3");

            // ---- 3: Fast and Auto both on: the trigger leaps, then his left click leaps too ----
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setLeapFast", door, true));
            giveLeap(c, 0);
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.setField(LM, "leapCdTicks", 0.0);
            });
            c.ctx().waitTicks(3);
            long mark = mark(c);
            int clicks = c.events("menu.action").size();
            c.hx().chat("Carol opened a WITHER door!");
            c.waitUntil("the Auto leap on Carol's door", mc -> c.events("menu.action").size() > clicks, 200);
            idle(c);
            int autoSlot = lastClick(c);
            giveLeap(c, 0);
            c.ctx().runOnClient(mc -> Mod.setField(LM, "leapCdTicks", 0.0));
            int clicks2 = c.events("menu.action").size();
            c.ctx().getInput().pressMouse(0);
            c.waitUntil("his left click's Fast leap", mc -> c.events("menu.action").size() > clicks2, 200);
            idle(c);
            int fastSlot = lastClick(c);
            List<String> seq = since(c, mark);
            int sel = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.note("Fast + Auto on: trigger clicked " + autoSlot + ", his click " + fastSlot + "; server saw " + seq
                    + "; holds slot " + (sel + 1));
            c.check(autoSlot == 15 && fastSlot == 15, "with Fast and Auto both on the trigger clicked " + autoSlot
                    + " and his click " + fastSlot + ", Carol (the opener) is 15 for both");
            c.check(seq.stream().noneMatch(s -> s.startsWith("held")), "a leap that started holding the leap swapped: " + seq);
            c.check(sel == 0, "he holds slot " + (sel + 1) + " after leaps from the held leap, want slot 1");
        } finally {
            restore(c, file, was);
        }
    }

    /**
     * Gives the leap to slot 5 and a sword to slot 1, holds slot 1, fires the Door Opener's Auto leap with {@code opener}'s
     * door line and checks the server saw held 5, use, the click, held {@code wantReturn}, each step on its own client
     * tick. @return {clicked slot, the slot he holds afterwards}
     */
    private static int[] autoLeap(Session c, String what, String opener, int wantReturn) {
        c.hx().give(0, "minecraft:diamond_sword");
        giveLeap(c, 4);
        c.ctx().runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            Mod.setField(LM, "leapCdTicks", 0.0);
        });
        c.ctx().waitTicks(3);
        long mark = mark(c);
        int clicks = c.events("menu.action").size();
        c.hx().chat(opener + " opened a WITHER door!");
        c.waitUntil(what + ": the leap click", mc -> c.events("menu.action").size() > clicks, 200);
        idle(c);
        c.ctx().waitTicks(3);
        int slot = lastClick(c);
        List<String> seq = since(c, mark);
        @SuppressWarnings("unchecked")
        List<String> steps = c.onClient(mc -> (List<String>) Mod.staticCall(LM, "stepLog"));
        int sel = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
        c.note(what + ": server saw " + seq + "; client steps " + steps + "; holds slot " + (sel + 1));
        List<String> want = List.of("held 4", "use_item", "click " + slot, "held " + wantReturn);
        c.check(seq.equals(want), what + ": the server saw " + seq + ", want " + want);
        c.check(steps.size() == 4 && steps.get(0).endsWith(" swap 5") && steps.get(1).endsWith(" use")
                && steps.get(2).endsWith(" click " + slot) && steps.get(3).endsWith(" return " + (wantReturn + 1)),
                what + ": client steps " + steps);
        long prev = Long.MIN_VALUE;
        for (String s : steps) {
            long t = Long.parseLong(s.substring(0, s.indexOf(' ')));
            c.check(t > prev, what + ": two steps on one tick: " + steps);
            prev = t;
        }
        return new int[]{slot, sel};
    }

    // =================================================================================================================
    // 1342 the failure messages
    // =================================================================================================================

    static void failures(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Fast/Auto Leap is cheat-only");
            return;
        }
        LogTap.install();
        Path file = configFile(c);
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        Object cfgObj = c.onClient(mc -> Mod.staticCall(FLC, "getInstance"));
        Object door = Mod.enumValue(FLC + "$LeapGroup", "DOOR");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            party(c, List.of("Alice", "Bob", "Carol", "Dave"));
            c.ctx().runOnClient(mc -> {
                Mod.call(cfgObj, "setTargetMode", Mod.enumValue(FLC + "$TargetMode", "PER_LEAP"));
                Mod.call(cfgObj, "setLeapFast", door, false);
                Mod.call(cfgObj, "setLeapAuto", door, true);
                Mod.call(cfgObj, "setReturnToSlot", -1);
            });

            // ---- 1: the leap is in the inventory, not the hotbar: say so, send nothing, move nothing ----
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", MenuKit.menu("menus.spirit-leap"));
            c.hx().give(0, "minecraft:diamond_sword");
            c.hx().give(4, "minecraft:air");
            c.hx().give(20, MenuKit.item("items.spirit-leap"));
            c.waitUntil("the leap in inventory slot 21", mc -> "SPIRIT_LEAP".equals(
                    Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(20))), 60);
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.setField(LM, "leapCdTicks", 0.0);
            });
            c.ctx().waitTicks(3);
            String line = failure(c, "no leap in the hotbar", "Alice", "No Spirit Leap or Infinileap in your hotbar");
            List<String> seq = since(c, lastMark);
            String inv20 = c.onClient(mc -> (String) Mod.staticCall("cheatutils.CheatUtils", "skyblockId",
                    mc.player.getInventory().getItem(20)));
            c.note("no leap in the hotbar: \"" + line + "\"; server saw " + seq + "; inventory slot 21 holds " + inv20);
            c.check(seq.isEmpty(), "with no leap in the hotbar the client still sent " + seq);
            c.check("SPIRIT_LEAP".equals(inv20), "the leap in the inventory was moved (slot 21 holds " + inv20 + ")");
            c.hx().give(20, "minecraft:air");

            // ---- 2: the menu does not open: say so, and still swap back ----
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            giveLeap(c, 4);
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.setField(LM, "leapCdTicks", 0.0);
            });
            c.ctx().waitTicks(3);
            line = failure(c, "menu did not open", "Bob", "the leap menu did not open");
            c.ctx().waitTicks(3);
            seq = since(c, lastMark);
            int sel = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.note("menu did not open: \"" + line + "\"; server saw " + seq + "; holds slot " + (sel + 1));
            c.check(seq.equals(List.of("held 4", "use_item", "held 0")), "menu did not open: the server saw " + seq
                    + ", want the swap, the use and the swap back");
            c.check(sel == 0, "after the failed leap he holds slot " + (sel + 1) + ", want slot 1");

            // ---- 3: the target is on the tab list but not in the menu ----
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", MenuKit.menu("menus.spirit-leap"));
            giveLeap(c, 4);
            c.ctx().runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.setField(LM, "leapCdTicks", 0.0);
            });
            c.ctx().waitTicks(3);
            line = failure(c, "target not in the menu", "Dave", "Dave: not in the leap menu");
            c.ctx().waitTicks(3);
            seq = since(c, lastMark);
            sel = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.note("target not in the menu: \"" + line + "\"; server saw " + seq + "; holds slot " + (sel + 1));
            c.check(seq.stream().noneMatch(s -> s.startsWith("click")), "a head was clicked for Dave: " + seq);
            c.check(sel == 0, "after the failed leap he holds slot " + (sel + 1) + ", want slot 1");
        } finally {
            restore(c, file, was);
        }
    }

    private static long lastMark;

    /** Fires {@code opener}'s door, waits for the leap to end, and returns the chat line containing {@code want}. */
    private static String failure(Session c, String what, String opener, String want) {
        long logMark = LogTap.mark();
        lastMark = mark(c);
        c.hx().chat(opener + " opened a WITHER door!");
        c.ctx().waitTicks(5);
        idle(c);
        String found = null;
        for (String l : LogTap.since(logMark)) {
            if (l.contains("[CHAT]") && l.contains(want)) {
                found = l.substring(l.indexOf("[CHAT]") + 7);
            }
        }
        c.check(found != null, what + ": no chat line containing \"" + want + "\"");
        return found;
    }

    // =================================================================================================================
    // helpers
    // =================================================================================================================

    private static Path configFile(Session c) {
        return c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-fastleap.json"));
    }

    private static void party(Session c, List<String> names) {
        DungeonMenuCases.inDungeon(c);
        List<String> tab = new ArrayList<>();
        String[] cls = {"Mage", "Healer", "Archer", "Tank"};
        for (int i = 0; i < names.size(); i++) {
            tab.add("§r[40] §r§a" + names.get(i) + " §r§f(§r§d" + cls[i % cls.length] + " L§r§f)");
        }
        c.hx().tab(tab, null, null);
        String last = names.get(names.size() - 1);
        c.waitUntil("Teammates to read the tab list", mc -> Mod.staticCall("fastleap.Teammates", "byName", last) != null, 100);
    }

    private static void giveLeap(Session c, int slot) {
        c.hx().give(slot, MenuKit.item("items.spirit-leap"));
        c.waitUntil("the leap in hotbar slot " + (slot + 1), mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(slot))), 60);
    }

    private static void idle(Session c) {
        c.waitUntil("LeapManager idle", mc -> !(Boolean) Mod.staticCall(LM, "isBusy"), 200);
    }

    private static int lastClick(Session c) {
        List<JsonObject> actions = c.events("menu.action");
        return actions.get(actions.size() - 1).get("slot").getAsInt();
    }

    /** The current length of the case's event stream, for {@link #since}. */
    private static long mark(Session c) {
        return c.events().size();
    }

    /** What the client sent since {@code mark}: "held N", "use_item", "click N" (the menu's own action slot). */
    private static List<String> since(Session c, long mark) {
        List<JsonObject> all = c.events();
        List<String> out = new ArrayList<>();
        for (int i = (int) mark; i < all.size(); i++) {
            JsonObject e = all.get(i);
            String type = e.get("type").getAsString();
            switch (type) {
                case "held" -> out.add("held " + e.get("slot").getAsInt());
                case "use_item" -> out.add("use_item");
                case "menu.action" -> out.add("click " + e.get("slot").getAsInt());
                default -> {
                }
            }
        }
        return out;
    }

    private static void restore(Session c, Path file, byte[] was) throws Exception {
        if (was != null) {
            Files.write(file, was);
        } else {
            Files.deleteIfExists(file);
        }
        c.ctx().runOnClient(mc -> Mod.staticCall(FLC, "load"));
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
        c.hx().give(0, "minecraft:air");
        c.hx().give(4, "minecraft:air");
        c.hx().tabClear();
        DungeonMenuCases.leaveDungeon(c);
        MenuKit.reset(c);
        System.out.println(String.format(Locale.ROOT, "[leap-v2] restored %s", file.getFileName()));
    }
}
