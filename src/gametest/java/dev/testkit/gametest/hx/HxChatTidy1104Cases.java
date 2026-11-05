package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chattidy-1104 (killer560, 2026-11-03: "add an option to hide these messages as well"). Three display-time hides:
 * Chat Hider's Hide Mining Reminders and Hide Sack Messages, and Chest Loot Compact's Hide Treasure Chest Uncovered. Each
 * case proves ON hides the real line (and the mod counted it), OFF shows it, and a player's copy is never hidden; the
 * treasure case also proves the alert and the chest claim still fire while the line is hidden (the hide runs after every
 * reader). WrongArmorAlert reads the held tool's lore, not chat, so the Breaking Power line needs no reader proof.
 */
final class HxChatTidy1104Cases {

    private static final String TIDY = "chattidy.ChatTidy";
    private static final String CFG = "chattidy.ChatTidyConfig";
    private static final String PCFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";

    private HxChatTidy1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chattidy-mining", HxChatTidy1104Cases::mining);
        s.test("1104-chattidy-sacks", HxChatTidy1104Cases::sacks);
        s.test("1104-chattidy-looted", HxChatTidy1104Cases::looted);
        s.test("1104-chattidy-treasure", HxChatTidy1104Cases::treasure);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        List<String> raw = c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", 40));
        return raw.stream().map(l -> l.replaceAll("§.", "")).toList();
    }

    private static boolean shown(Session c, String exact) {
        return lines(c).contains(exact);
    }

    private static long hiddenCount(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(TIDY, "testHidden")).longValue());
    }

    private static void say(Session c, String legacy) {
        c.hx().chat(legacy);
        c.ctx().waitTicks(6);
    }

    private static void skyblock(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.ctx().waitTicks(10);
    }

    /** Runs {@code body} with Chat Hider on and the given Chat Hider switch set; everything restored after. */
    private static void withSwitch(Session c, String prop, boolean value, Runnable body) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean enabledWas = c.onClient(mc -> (Boolean) Mod.call(cfg, "getEnabledRaw"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "get" + prop + "Raw"));
        c.ctx().runOnClient(mc -> {
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "set" + prop, value);
        });
        try {
            body.run();
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "set" + prop, was);
                Mod.call(cfg, "setEnabled", enabledWas);
            });
        }
    }

    private static void mining(Session c) {
        hub(c);
        skyblock(c);
        String bp = "You need a tool with a Breaking Power of 7 to mine Amethyst Gemstone Block! Speak to Fragilis by the entrance to the Crystal Hollows to learn more!";
        String bpOne = "You need a tool with a Breaking Power of 7 to mine Amethyst Gemstone Block!";
        String fragilis = "Speak to Fragilis by the entrance to the Crystal Hollows to learn more!";
        String tokens = "You have unspent Tokens of the Mountain! Use /hotm to assign them!";
        String bobBp = "[VIP] Bob: " + bp;
        String bobTokens = "[MVP+] Eve: " + tokens;
        withSwitch(c, "HideMiningReminders", true, () -> {
            long h0 = hiddenCount(c);
            say(c, "§c" + bp);
            say(c, "§c" + bpOne);
            say(c, "§c" + fragilis);
            say(c, "§c" + tokens);
            c.check(hiddenCount(c) - h0 == 4, "ON: the mod hid " + (hiddenCount(c) - h0) + " of the 4 reminder lines");
            c.check(!shown(c, bp) && !shown(c, bpOne) && !shown(c, fragilis) && !shown(c, tokens),
                    "ON: a reminder line is still in the chat window: " + lines(c));
            say(c, "§7[VIP] Bob§f: " + bp);
            say(c, "§7[MVP+] Eve§f: " + tokens);
            c.check(shown(c, bobBp) && shown(c, bobTokens), "ON: a player's copy was hidden: " + lines(c));
        });
        withSwitch(c, "HideMiningReminders", false, () -> {
            long h0 = hiddenCount(c);
            say(c, "§c" + bp);
            say(c, "§c" + tokens);
            c.check(hiddenCount(c) == h0, "OFF: the mod still hid a line");
            c.check(shown(c, bp) && shown(c, tokens), "OFF: the reminder lines are not in the chat window: " + lines(c));
        });
    }

    private static void sacks(Session c) {
        hub(c);
        skyblock(c);
        String plus = "[Sacks] +136 items. (Last 7s.)";
        String minus = "[Sacks] -20,140 items. (Last 30s.)";
        String both = "[Sacks] +3,587 items, -20,140 items. (Last 30s.)";
        String one = "[Sacks] +1 item. (Last 5s.)";
        withSwitch(c, "HideSackMessages", true, () -> {
            long h0 = hiddenCount(c);
            say(c, "§6" + plus);
            say(c, "§6" + minus);
            say(c, "§6" + both);
            say(c, "§6" + one);
            c.check(hiddenCount(c) - h0 == 4, "ON: the mod hid " + (hiddenCount(c) - h0) + " of the 4 sack lines");
            c.check(!shown(c, plus) && !shown(c, minus) && !shown(c, both) && !shown(c, one),
                    "ON: a sack line is still in the chat window: " + lines(c));
            say(c, "§7[VIP] Bob§f: " + plus);
            c.check(shown(c, "[VIP] Bob: " + plus), "ON: a player's copy was hidden: " + lines(c));
        });
        withSwitch(c, "HideSackMessages", false, () -> {
            long h0 = hiddenCount(c);
            say(c, "§6" + plus);
            c.check(hiddenCount(c) == h0 && shown(c, plus), "OFF: the sack line was hidden: " + lines(c));
        });
    }

    private static void looted(Session c) {
        hub(c);
        skyblock(c);
        String line = "This chest has already been looted.";
        withSwitch(c, "HideAlreadyLooted", true, () -> {
            long h0 = hiddenCount(c);
            say(c, "§c" + line);
            c.check(hiddenCount(c) - h0 == 1, "ON: the mod hid " + (hiddenCount(c) - h0) + " of the looted line");
            c.check(!shown(c, line), "ON: the line is in the chat window: " + lines(c));
            say(c, "§7[VIP] Bob§f: " + line);
            c.check(shown(c, "[VIP] Bob: " + line), "ON: a player's copy was hidden: " + lines(c));
        });
        withSwitch(c, "HideAlreadyLooted", false, () -> {
            long h0 = hiddenCount(c);
            say(c, "§c" + line);
            c.check(hiddenCount(c) == h0 && shown(c, line), "OFF: the line was hidden: " + lines(c));
        });
    }

    private static void treasure(Session c) {
        int[] f = c.onClient(mc -> new int[]{(int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY()),
                (int) Math.floor(mc.player.getZ())});
        Object cfg = c.onClient(mc -> Mod.cfg(PCFG));
        boolean hideWas = c.onClient(mc -> (Boolean) Mod.call(cfg, "isHideTreasureUncoveredRaw"));
        boolean alertWas = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestAlertRaw"));
        boolean chestWas = c.onClient(mc -> (Boolean) Mod.call(cfg, "isTreasureChestRaw"));
        Settings s0 = new Settings(c);
        try (Settings set = s0.with(PCFG, "ChestAlertSound", false).with(PCFG, "ChestAlertTitle", false)) {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestAlert", true);
                Mod.call(cfg, "setTreasureChest", true);
            });
            hub(c);
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
            c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
            c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);

            c.ctx().runOnClient(mc -> Mod.call(cfg, "setHideTreasureUncovered", true));
            int a0 = c.onClient(mc -> ((Number) Mod.staticCall(TC, "alerts")).intValue());
            long h0 = hiddenCount(c);
            say(c, UNCOVERED);
            int a1 = c.onClient(mc -> ((Number) Mod.staticCall(TC, "alerts")).intValue());
            c.check(a1 == a0 + 1, "ON: the alert did not fire while the line is hidden (" + a0 + " -> " + a1 + ")");
            c.check(hiddenCount(c) - h0 == 1, "ON: the mod did not count the hide");
            c.check(!shown(c, "You uncovered a treasure chest!"), "ON: the line is in the chat window: " + lines(c));
            int bx = f[0] + 3, by = f[1], bz = f[2];
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", bx, by, bz));
            c.waitUntil("the hidden line still claims the chest",
                    mc -> (Boolean) Mod.staticCall(TC, "tracked", bx, by, bz), 40);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", bx, by, bz));
            say(c, "§7[VIP] Bob§f: You uncovered a treasure chest!");
            c.check(shown(c, "[VIP] Bob: You uncovered a treasure chest!"), "ON: a player's copy was hidden: " + lines(c));

            c.ctx().runOnClient(mc -> Mod.call(cfg, "setHideTreasureUncovered", false));
            long h1 = hiddenCount(c);
            say(c, UNCOVERED);
            c.check(hiddenCount(c) == h1 && shown(c, "You uncovered a treasure chest!"),
                    "OFF: the line was hidden: " + lines(c));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setHideTreasureUncovered", hideWas);
                Mod.call(cfg, "setChestAlert", alertWas);
                Mod.call(cfg, "setTreasureChest", chestWas);
            });
        }
    }
}
