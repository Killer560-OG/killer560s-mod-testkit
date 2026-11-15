package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1113-boss-breaker-refill} (simbrkrefill-1113, killer560 11-13: "if i ever reset a section or something then reset my
 * breaker to max breaks"). Charges are spent down to a few, then {@code /simboss s2} resets the section: the Dungeon Breaker
 * must be back at its 20 charges at once (a 2-a-second recharge could not have refilled it in the time waited).
 */
final class SimBossBreakerRefill1113 {

    static final String NAME = "1113-boss-breaker-refill";
    private static final String CHARGES = "com.killer560.hub.roomsim.SimBreakerState";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";
    private static final String SIM = "com.killer560.hub.bosssim.BossSim";

    private SimBossBreakerRefill1113() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S2", true);
        for (int i = 0; i < 17; i++) {
            call(CHARGES, "trySpend");
        }
        int spent = (Integer) call(CHARGES, "charges");
        println("boss", "charges after spending: " + spent);
        expect(f, "charges were spent (control, at most 4 left)", spent <= 4, true);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("simboss s2"));
        boolean back = until(ctx, "the section reset", mc -> !(Boolean) call(SIM, "isBuilding")
                && (Boolean) call(FIGHT, "running"), 2400);
        expect(f, "the command reset the fight", back, true);
        int after = (Integer) call(CHARGES, "charges");
        println("boss", "charges after the reset: " + after);
        expect(f, "charges back at max after the reset (19 or 20)", after >= 19, true);
    }
}
