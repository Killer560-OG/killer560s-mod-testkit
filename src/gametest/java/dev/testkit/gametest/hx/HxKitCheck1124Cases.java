package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.hx.HxKit.Settings;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Mod idea-kitcheck-1124 (killer560, 2026-11-23: "sure that sounds good"). 1124-kitcheck drives the real chat path
 * (a "Starting in 5 seconds." line) in an F7 sidebar and asserts: a short kit (2 of 4 leaps, 1 of 2 Superboom TNT, no God
 * Potion in the footer) is reported by name and count, a full kit is checked and reported clean, the same countdown is
 * checked once, an empty footer never reports a God Potion, a Booster Cookie that is not active is reported, nothing
 * outside a dungeon runs, and the inventory and selected slot are untouched (it never clicks, swaps or equips).
 */
final class HxKitCheck1124Cases {

    private static final String CFG = "kitcheck.KitCheckConfig";
    private static final String F = "kitcheck.KitCheckFeature";
    private static final String LEAP = "minecraft:ender_pearl[minecraft:custom_data={id:\"SPIRIT_LEAP\"}]";
    private static final String BOOM = "minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}]";
    private static final String GOD = "§aActive Effects\n§dYou have a §cGod Potion §dactive! §f22:00:00";
    private static final String NO_GOD = "§aActive Effects\n§7Strength";

    private HxKitCheck1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-kitcheck", HxKitCheck1124Cases::kitCheck);
    }

    private static int checks(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(F, "checks"));
    }

    private static String report(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "lastReport"));
    }

    private static void kit(Session c, int leaps, int boom, String footer) {
        for (int i = 0; i < 8; i++) {
            c.hx().give(i, i < leaps ? LEAP : i >= 4 && i < 4 + boom ? BOOM : "minecraft:air");
        }
        c.hx().tab(List.of("§r§b§lArea: §r§7Catacombs"), null, footer);
        c.ctx().waitTicks(15);
    }

    /** One countdown line through the real chat path; waits for the check (or, when {@code expect} is false, for nothing). */
    private static void countdown(Session c, int secs, boolean expect, String what) {
        int base = checks(c);
        c.onClient(mc -> {
            Mod.staticCall(F, "resetForTest");
            mc.player.sendSystemMessage(Component.literal("Starting in " + secs + " seconds."));
            return null;
        });
        if (expect) {
            c.waitUntil(what, mc -> (Integer) Mod.staticCall(F, "checks") == base + 1, 20);
        } else {
            c.ctx().waitTicks(15);
            c.check(checks(c) == base, what + ": a check ran (" + checks(c) + " vs " + base + ")");
        }
    }

    private static void kitCheck(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "MinFloor", 7).with(CFG, "Leaps", 4)
                .with(CFG, "Boom", 2).with(CFG, "Decoys", 0).with(CFG, "Arrows", 0).with(CFG, "GodPotion", true)
                .with(CFG, "Cookie", false)) {
            HxKit.dungeon(c);
            int slot = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            // Short kit: named with counts.
            kit(c, 2, 1, NO_GOD);
            countdown(c, 5, true, "the short-kit check");
            String r = report(c);
            c.check(r != null && r.contains("Spirit Leap 2/4") && r.contains("Superboom TNT 1/2") && r.contains("God Potion"),
                    "short-kit report was '" + r + "'");
            // Full kit: checked, nothing short.
            kit(c, 4, 2, GOD);
            countdown(c, 5, true, "the full-kit check");
            c.check(report(c) == null, "a full kit reported '" + report(c) + "'");
            // Once per countdown: the next line of the same countdown does not check again.
            int base = checks(c);
            c.onClient(mc -> {
                mc.player.sendSystemMessage(Component.literal("Starting in 4 seconds."));
                return null;
            });
            c.ctx().waitTicks(15);
            c.check(checks(c) == base, "the same countdown checked twice (" + checks(c) + " vs " + base + ")");
            // A footer that has not loaded never reports a God Potion.
            kit(c, 4, 2, "");
            countdown(c, 5, true, "the empty-footer check");
            c.check(report(c) == null, "an empty footer reported '" + report(c) + "'");
            // A Booster Cookie that is not active.
            try (Settings cookie = new Settings(c).with(CFG, "Cookie", true)) {
                kit(c, 4, 2, GOD + "\n§6Cookie Buff\n§7Not active! Obtain booster cookies from the community shop");
                countdown(c, 5, true, "the cookie check");
                String cr = report(c);
                c.check(cr != null && cr.contains("Booster Cookie") && !cr.contains("Spirit"), "cookie report was '" + cr + "'");
            }
            // Never clicks, swaps or equips: the selected slot and the counts are as they were.
            c.check(c.onClient(mc -> mc.player.getInventory().getSelectedSlot()) == slot, "the selected slot moved");
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(3).getCount()) == 1, "the leap stack changed");
            // Outside a dungeon nothing runs.
            HxKit.hub(c);
            countdown(c, 5, false, "the hub countdown");
        }
    }
}
