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
        MenuSuite.test(s, "1123-menu-beacon-tune2", BeaconCases::tune2);
        MenuSuite.test(s, "1123-menu-beacon-pause", BeaconCases::pause);
        BeaconAutoCases.register(s);
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
                    java.util.Arrays.stream(lore.split("[|]", -1)).map(l -> (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.literal(l)).toList()));
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
            String last = null;
            int i = 0;
            while (i < rows.size()) {
                int tick = Integer.parseInt(rows.get(i)[0]);
                for (int t : at) { // a requested tick with no recorded row: what the tuner said after the last row before it
                    if (t < tick && last != null && !out.containsKey(t)) {
                        out.put(t, last);
                    }
                }
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
                last = String.join(" // ", (String[]) Mod.staticCall(TUNER, "lines")) + " || " + String.join(",", (String[]) Mod.staticCall(TUNER, "hints"));
                for (int t : at) {
                    if (t == tick) {
                        out.put(t, last);
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
                Mod.staticCall(SOLVER, "presses", 11, 12, 13), Mod.staticCall(SOLVER, "presses", 12, 0, 13), Mod.staticCall(SOLVER, "presses", 2, 0, 3)});
        c.check(java.util.Arrays.equals(u, new Object[]{5, 1, 3, 0, 1, 2, 1, -1, 1, 1, 1}), "arithmetic read " + java.util.Arrays.toString(u));
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

    /**
     * A second recorded tune (11-23 6:32 PM): a yellow beacon beat at speed 4 and High, the player starting magenta, speed 3,
     * High, stepping Speed 5 to 1, Pitch High to Low and Color red to white as the wraps the recording proved. Replays it and
     * checks what the tuner said with Pitch at Low (a right click wraps to High) and while paused.
     */
    private static void tune2(Session c) throws Exception {
        java.util.Map<Integer, String> r = replay(c, "beacon-tune2-1123.tsv", false, 1348, 1415);
        String a = r.get(1348);
        c.note("tune2 replay: Low " + a + " ## paused " + r.get(1415));
        c.check(a != null && a.startsWith("Color: left x2 | Speed: left x1 | Pitch: right x1") && a.contains("(beacon: yellow, speed 4, high)") && a.endsWith("|| 46:L2,48:L1,50:R1"),
                "yellow beacon beat read from the replay: " + a);
        String p = r.get(1415);
        c.check(p != null && p.contains("(beacon: yellow, speed 4, high)"), "still reading the beacon while paused: " + p);
    }

    /**
     * The recorded Pause (scan 1): once the speeds matched and you had paused, the tuner counts the beacon's steps onto your slot
     * (3 at tick 10930) and says to unpause at tick 11093, the step where the beacon reached it (the player unpaused at 11097
     * and both panes stepped to the same next slot together at 11149).
     */
    private static void pause(Session c) throws Exception {
        java.util.Map<Integer, String> r = replay(c, "beacon-tune-1123.tsv", false, 10930, 11093);
        String a = r.get(10930);
        String b = r.get(11093);
        c.note("pause replay: 10930 " + a + " ## 11093 " + b);
        c.check(a != null && a.contains("paused: unpause when the beacon steps onto your slot (3 steps)") && a.endsWith("52:U"), "paused three slots short read " + a);
        c.check(b != null && b.contains("in line: unpause now") && b.endsWith("52:U"), "paused on the beacon's slot read " + b);
    }

    /** The recorded Upgrade Signal Strength menu with its real button lore (6:53 PM scan): both beacon beats measured, both of your beats readable. */
    private static void upgrade(Session c) throws Exception {
        java.util.Map<Integer, String> r = replay(c, "beacon-upgrade2-1123.tsv", true, 569);
        String s = r.get(569);
        c.note("upgrade replay: " + s);
        c.check(s != null && s.contains("(beacon: green, speed 4, normal)") && s.contains("(beacon: magenta, speed 1, high)"),
                "both beacon beats (green speed 4 Normal, magenta speed 1 High) measured: " + s);
        c.check(s != null && s.contains("Beat 1: ") && s.contains("Beat 2: ") && !s.contains("not readable"), "two player beats listed and readable from the lore: " + s);
    }
}
