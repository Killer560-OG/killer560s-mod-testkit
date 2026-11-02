package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>1101-rush-splits</b> (mod doc-adm-sim-1101, the Doc's "blood rush split stuff" from the reference mod adm): Blood Rush
 * Splits driven through its test hooks with exact times - no world, so every number is checked to the millisecond:
 * <pre>
 *   0      [NPC] Mort: ...                       room 1 starts
 *   8000   a Wither Key stand appears in Pipes   room 1 is Pipes, last mob at 8.00
 *   8200   [MVP+] Bob has obtained Wither Key!   (a rank in front)
 *   8500   Bob opened a WITHER door!             room 1 total 8.50, key 8.20, door 0.30 (shown: over 0.25)
 *   9100   that door down: Pipes | Duncan        room 2 is Duncan
 *   15000  key stand in Duncan
 *   15600  A Wither Key was picked up!           key from door down 6.50, pickup 0.60 (slow: dark red)
 *   15700  Bob opened a WITHER door!             room 2 total 7.20, door 0.10 (not shown)
 *   16300  door down: Fairy | Mural              the door out of Fairy: Duncan led into Fairy (pink)
 *   22000  Bob has obtained Blood Key!           room 3 Mural, key 5.70 (no stand seen: put at the pickup)
 *   22100  The BLOOD DOOR has been opened!       room 3 total 6.40; Average 7.37
 * </pre>
 * A control first: with no Mort line nothing starts and no row is shown.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BloodRushSplitsTests implements FabricClientGameTest {

    static final String NAME = "1101-rush-splits";
    private static final String RUSH = "com.killer560.hub.splittimers.BloodRushSplits";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(20);
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            call("resetForTest");
            // Control: door and key lines with no Mort line - nothing runs.
            line("Bob opened a WITHER door!", 100);
            line("Bob has obtained Wither Key!", 200);
            List<String> none = rows(300);
            if (!none.isEmpty()) {
                problems.add("control: rows without Mort's line: " + none);
            }
            line("[NPC] Mort: Here, I found this map when I first entered the dungeon.", 0);
            key("Pipes", 3.0, 8000);
            line("[MVP+] Bob has obtained Wither Key!", 8200);
            line("Bob opened a WITHER door!", 8500);
            door("Pipes", false, "Duncan", 9100);
            key("Duncan", 4.0, 15000);
            line("A Wither Key was picked up!", 15600);
            line("Bob opened a WITHER door!", 15700);
            door("Fairy", true, "Mural", 16300);
            line("Bob has obtained Blood Key!", 22000);
            line("The BLOOD DOOR has been opened!", 22100);
            List<String> r = rows(30000);
            for (String s : r) {
                System.out.println("[" + NAME + "] row: " + s);
            }
            expect(problems, r, "§5Pipes§f: 8.50s §8| §ckey 8.20s §8| §7door 0.30s");
            expect(problems, r, "§dDuncan§f: 7.20s §8| §4key 6.50s");
            expect(problems, r, "§5Mural§f: 6.40s §8| §ckey 5.70s");
            boolean avg = r.stream().anyMatch(s -> s.startsWith("§6Average§f: 7.37s"));
            if (!avg) {
                problems.add("no Average 7.37s row");
            }
            List<String> detailed = rowsDetailed(30000);
            for (String s : detailed) {
                System.out.println("[" + NAME + "] detailed: " + s);
            }
            if (!detailed.contains("§c Pickup§f: 0.60s") || !detailed.contains("§6 Room Total§f: 7.20s")
                    || !detailed.contains("§8 Door Down§f: 0.60s")) {
                problems.add("Detailed rows missing Duncan's Pickup 0.60s / Room Total 7.20s / Door Down 0.60s");
            }
            call("resetForTest");
        });
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void expect(List<String> problems, List<String> rows, String want) {
        if (!rows.contains(want)) {
            problems.add("missing row \"" + want.replaceAll("§.", "") + "\"");
        }
    }

    private static void call(String m) {
        ModUnderTest.staticCall(RUSH, m);
    }

    private static void line(String raw, long ms) {
        ModUnderTest.staticCall(RUSH, "lineForTest", new Class<?>[]{String.class, long.class, long.class},
                new Object[]{raw, ms, 0L});
    }

    private static void key(String room, double d, long ms) {
        ModUnderTest.staticCall(RUSH, "keyForTest", new Class<?>[]{String.class, double.class, long.class},
                new Object[]{room, d, ms});
    }

    private static void door(String earlier, boolean fairy, String later, long ms) {
        ModUnderTest.staticCall(RUSH, "doorDownForTest", new Class<?>[]{String.class, boolean.class, String.class, long.class},
                new Object[]{earlier, fairy, later, ms});
    }

    @SuppressWarnings("unchecked")
    private static List<String> rows(long ms) {
        return (List<String>) ModUnderTest.staticCall(RUSH, "rowsForTest", new Class<?>[]{long.class, String.class},
                new Object[]{ms, "COMPACT"});
    }

    @SuppressWarnings("unchecked")
    private static List<String> rowsDetailed(long ms) {
        return (List<String>) ModUnderTest.staticCall(RUSH, "rowsForTest", new Class<?>[]{long.class, String.class},
                new Object[]{ms, "DETAILED"});
    }
}
