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
        MenuSuite.test(s, "1123-menu-beacon-upgrade", BeaconCases::upgrade);
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

    private static final String TUNER = "foraging.BeaconTuner";
    private static final String SOLVER = "foraging.BeaconTuneSolver";

    /** The recorded menu as rows: tick, kind (S slot item name lore, N note pitch, K click, D done), fields. */
    private static List<String[]> fixture(String name) throws Exception {
        try (java.io.InputStream in = BeaconCases.class.getResourceAsStream("/testkit-ui/" + name)) {
            return new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).lines()
                    .map(l -> l.split("\t", -1)).toList();
        }
    }

    private static ItemStack stack(String path, String name, String lore) {
        ItemStack st = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.Identifier.withDefaultNamespace(path)));
        if (!lore.isEmpty()) {
            st.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                    java.util.Arrays.stream(lore.split("\|", -1)).map(l -> (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.literal(l)).toList()));
        }
        return st;
    }

    /**
     * Replays a recorded window through the tuner tick by tick and returns what it said at each requested tick:
     * "lines || hints" (the tuner reads the same slots and sounds the real client got).
     */
    private static java.util.Map<Integer, String> replay(Session c, String file, boolean upgrade, int... at) throws Exception {
        List<String[]> rows = fixture(file);
        return c.onClient(mc -> {
            ItemStack[] items = new ItemStack[54];
            java.util.Arrays.fill(items, ItemStack.EMPTY);
            Mod.staticCall(TUNER, "reset");
            java.util.Map<Integer, String> out = new java.util.HashMap<>();
            int i = 0;
            while (i < rows.size()) {
                int tick = Integer.parseInt(rows.get(i)[0]);
                for (; i < rows.size() && Integer.parseInt(rows.get(i)[0]) == tick; i++) {
                    String[] r = rows.get(i);
                    if (r[1].equals("S")) {
                        items[Integer.parseInt(r[2])] = stack(r[3], r[4], r[5]);
                    } else if (r[1].equals("N")) {
                        Mod.staticCall(TUNER, "sound", Float.parseFloat(r[2]), (long) tick);
                    }
                }
                java.util.function.IntFunction<ItemStack> item = k -> items[k];
                Mod.staticCall(TUNER, "step", item, upgrade, (long) tick);
                for (int t : at) {
                    if (t == tick) {
                        out.put(t, String.join(" // ", (String[]) Mod.staticCall(TUNER, "lines")) + " || " + String.join(",", (String[]) Mod.staticCall(TUNER, "hints")));
                    }
                }
            }
            return out;
        });
    }

    /** The arithmetic, then the real recorded Tune Frequency menu replayed: what the tuner said before the tune that worked. */
    private static void tuner(Session c) throws Exception {
        Object[] u = c.onClient(mc -> new Object[]{Mod.staticCall(SOLVER, "speedFromPeriod", 15), Mod.staticCall(SOLVER, "speedFromPeriod", 55),
                Mod.staticCall(SOLVER, "speedFromPeriod", 35), Mod.staticCall(SOLVER, "pitchLevel", 0.095f), Mod.staticCall(SOLVER, "pitchLevel", 0.794f),
                Mod.staticCall(SOLVER, "pitchLevel", 1.492f), Mod.staticCall(SOLVER, "presses", 5, 1, 5), Mod.staticCall(SOLVER, "presses", 0, 2, 3),
                Mod.staticCall(SOLVER, "presses", 11, 12, 13), Mod.staticCall(SOLVER, "presses", 12, 0, 13)});
        c.check(java.util.Arrays.equals(u, new Object[]{5, 1, 3, 0, 1, 2, 1, -1, 1, 1}), "arithmetic read " + java.util.Arrays.toString(u));
        java.util.Map<Integer, String> r = replay(c, "beacon-tune-1123.tsv", false, 10823, 10873, 11149);
        c.note("tune replay: first " + r.get(10823) + " ## second step " + r.get(10873) + " ## last " + r.get(11149));
        String a = r.get(10823);
        c.check(a != null && a.startsWith("Color: left x6 | Speed: waiting for a beat | Pitch: waiting for a beat"), "start (lime vs the green beat, nothing measured yet) read " + a);
        String b = r.get(10873);
        c.check(b != null && b.startsWith("Color: left x6 | Speed: left x1 | Pitch: right x1") && b.contains("(beacon: green, speed 1, high)"), "after one beacon step read " + b);
        String z = r.get(11149);
        c.check(z != null && z.startsWith("Color: matched | Speed: matched | Pitch: left x1 | in step (beacon: green, speed 1, high) || 50:L1"),
                "the moment before the working click (Pitch left, as the player did) read " + z);
    }

    /** The recorded Upgrade Signal Strength menu (two beats; its buttons' lore was not recorded): both beacon beats measured. */
    private static void upgrade(Session c) throws Exception {
        java.util.Map<Integer, String> r = replay(c, "beacon-upgrade-1123.tsv", true, 11700);
        String s = r.get(11700);
        c.note("upgrade replay: " + s);
        c.check(s != null && s.contains("speed 3") && s.contains("speed 2") && s.contains("purple") && s.contains("light blue"),
                "both beacon beats (purple speed 3, light blue speed 2) measured: " + s);
        c.check(s != null && s.contains("Beat 1: ") && s.contains("Beat 2: "), "two player beats listed: " + s);
    }
}
