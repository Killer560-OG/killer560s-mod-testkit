package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 1121-sim-witherdoor-anyitem (mod sim-witherdoor-1121, killer560 2026-11-21: "dropping wither doors should be able to be
 * done with any item if i have the key"): with the team's key a right click on a wither door opens it whatever is in hand
 * (Dungeonbreaker, AOTV, Hyperion, Terminator bow, Superboom, empty hand) and the key count drops by one; without a key the
 * door stays shut. The Blood door with the Blood Key likewise.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWitherDoorAnyItem1121Tests implements FabricClientGameTest {
    private static final String NAME = "1121-sim-witherdoor-anyitem";
    private static final String[] HELD = {"DUNGEONBREAKER", "ASPECT_OF_THE_VOID", "HYPERION", "TERMINATOR", "SUPERBOOM_TNT", null};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            SimWitherDoor1121.enter(ctx, sp);
            int opened = 0;
            StringBuilder detail = new StringBuilder();
            for (String item : HELD) {
                String label = item == null ? "empty hand" : item;
                // Wither door WITH a key: opens, key spent.
                var d = SimWitherDoor1121.fresh(ctx, sp, SimWitherDoor1121.WITHER);
                if (d.blocks().isEmpty()) {
                    failures.add(label + ": no wither door blocks were registered");
                    continue;
                }
                SimWitherDoor1121.setKeys(1, false);
                SimWitherDoor1121.hold(ctx, sp, item);
                int[] before = SimWitherDoor1121.solid(ctx, sp, d);
                SimWitherDoor1121.rightClick(ctx, d.target());
                ctx.waitTicks(SimWitherDoor1121.openDelay(ctx) + 30);
                int[] after = SimWitherDoor1121.solid(ctx, sp, d);
                int keysLeft = SimWitherDoor1121.witherKeys();
                boolean ok = before[0] > 0 && after[0] == 0 && after[1] == 0 && keysLeft == 0;
                if (ok) opened++;
                else failures.add(label + " with key: door blocks " + before[0] + " -> server " + after[0] + " client " + after[1] + ", keys left " + keysLeft);
                // Wither door WITHOUT a key: stays shut.
                var n = SimWitherDoor1121.fresh(ctx, sp, SimWitherDoor1121.WITHER);
                SimWitherDoor1121.setKeys(0, false);
                SimWitherDoor1121.hold(ctx, sp, item);
                int[] nb = SimWitherDoor1121.solid(ctx, sp, n);
                SimWitherDoor1121.rightClick(ctx, n.target());
                ctx.waitTicks(SimWitherDoor1121.openDelay(ctx) + 30);
                int[] na = SimWitherDoor1121.solid(ctx, sp, n);
                if (nb[0] == 0 || na[0] != nb[0]) failures.add(label + " without key: door blocks " + nb[0] + " -> " + na[0]);
                detail.append(label).append(ok ? " ok; " : " FAIL; ");
            }
            // Blood door with the Blood Key and a non-key item in hand.
            var b = SimWitherDoor1121.fresh(ctx, sp, SimWitherDoor1121.BLOOD);
            SimWitherDoor1121.setKeys(0, true);
            SimWitherDoor1121.hold(ctx, sp, "HYPERION");
            int bb = SimWitherDoor1121.solid(ctx, sp, b)[0];
            SimWitherDoor1121.rightClick(ctx, b.target());
            ctx.waitTicks(SimWitherDoor1121.openDelay(ctx) + 30);
            int[] ba = SimWitherDoor1121.solid(ctx, sp, b);
            if (bb == 0 || ba[0] != 0 || ba[1] != 0 || SimWitherDoor1121.bloodKey()) {
                failures.add("blood door with the Blood Key and Hyperion: blocks " + bb + " -> " + ba[0] + "/" + ba[1] + ", key still held " + SimWitherDoor1121.bloodKey());
            }
            SimWitherDoor1121.row(failures, NAME, failures.isEmpty(), opened + "/" + HELD.length + " held items opened a wither door: " + detail
                    + "blood door with Hyperion " + (ba[0] == 0));
        } finally {
            SimWitherDoor1121.leave();
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join(" | ", failures));
        }
    }
}
