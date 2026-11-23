package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Moonglade Beacon cases (foragebeacon-1122): 1122-menu-beacon-ready feeds tab lists to Beacon Ready Alert and asserts the
 * alert fired only for the beacon widget's "Cooldown: AVAILABLE", once while it stays available, again after Repeat After.
 */
final class BeaconCases {

    private static final String F = "foraging.BeaconReadyAlert";

    private BeaconCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1122-menu-beacon-ready", BeaconCases::ready);
        MenuSuite.test(s, "1122-menu-beacon-tuner", BeaconCases::tuner);
    }

    private static void ready(Session c) throws Exception {
        List<String> cooling = List.of("Area: Moonglade Marsh", "Moonglade Beacon: 3 Stacks", " Cooldown: 4m 12s", " Tuned: Speed");
        List<String> ready = List.of("Area: Moonglade Marsh", "Moonglade Beacon: 3 Stacks", " Cooldown: AVAILABLE", " Tuned: Speed");
        List<String> other = List.of("Pickaxe Ability:", " Cooldown: AVAILABLE");
        long now = System.currentTimeMillis();
        int[] n = c.onClient(mc -> {
            Mod.call(Mod.field(F, "CFG"), "set", "repeat", 9);
            int a0 = (Integer) Mod.staticCall(F, "alerts");
            Mod.staticCall(F, "onTab", other, now);
            int a1 = (Integer) Mod.staticCall(F, "alerts");
            Mod.staticCall(F, "onTab", cooling, now);
            int a2 = (Integer) Mod.staticCall(F, "alerts");
            Mod.staticCall(F, "onTab", ready, now + 1000);
            Mod.staticCall(F, "onTab", ready, now + 2000);
            int a3 = (Integer) Mod.staticCall(F, "alerts");
            Mod.staticCall(F, "onTab", ready, now + 10 * 60_000L);
            int a4 = (Integer) Mod.staticCall(F, "alerts");
            return new int[]{a1 - a0, a2 - a0, a3 - a0, a4 - a0};
        });
        c.note("alerts: other widget " + n[0] + ", cooling " + n[1] + ", ready twice " + n[2] + ", ready 10 min later " + n[3]);
        c.check(n[0] == 0, "another widget's Cooldown: AVAILABLE fired the alert");
        c.check(n[1] == 0, "a cooling beacon fired the alert");
        c.check(n[2] == 1, "a ready beacon read twice fired " + n[2] + " alerts, not 1");
        c.check(n[3] == 2, "still ready after Repeat After did not alert again");
    }

    private static String solve(Session c, List<ItemStack> cur, List<ItemStack> tgt) throws Exception {
        String[] r = c.onClient(mc -> (String[]) Mod.staticCall("foraging.BeaconTuneSolver", "describeStacks", cur, tgt));
        return String.join(" | ", r);
    }

    private static List<ItemStack> dials(net.minecraft.world.item.Item color, int speed, int pitch) {
        return List.of(new ItemStack(color), new ItemStack(Items.REDSTONE_BLOCK, speed), new ItemStack(Items.NOTE_BLOCK, pitch));
    }

    /** The tuner's presses for the dial items of four menu states: off by some, wrapping color, off everywhere, matched. */
    private static void tuner(Session c) throws Exception {
        String a = solve(c, dials(Items.RED_STAINED_GLASS_PANE, 3, 4), dials(Items.BLUE_STAINED_GLASS_PANE, 5, 4));
        String b = solve(c, dials(Items.WHITE_STAINED_GLASS_PANE, 5, 1), dials(Items.BLACK_STAINED_GLASS_PANE, 2, 7));
        String m = solve(c, dials(Items.LIME_STAINED_GLASS_PANE, 3, 3), dials(Items.LIME_STAINED_GLASS_PANE, 3, 3));
        String u = solve(c, dials(Items.STONE, 3, 3), dials(Items.LIME_STAINED_GLASS_PANE, 3, 3));
        c.note("tuner states: " + a + " // " + b + " // " + m + " // " + u);
        c.check("Color: down x3 | Speed: up x2 | Pitch: matched".equals(a), "state 1 read " + a);
        c.check("Color: down x1 | Speed: down x3 | Pitch: up x6".equals(b), "state 2 (white to black wraps one way) read " + b);
        c.check("Color: matched | Speed: matched | Pitch: matched".equals(m), "already matched read " + m);
        c.check(u.startsWith("Color: not readable"), "a non-pane color dial read " + u);
    }
}
