package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.Entity;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1114-boss-left-click-terminal} (simtermlclick-1114, killer560 2026-11-14: "on sim make it so i can open the
 * terminals with left click as well"). In S1 a real left click (attack) on a terminal's stand opens its window, a right click
 * still does, and neither damages the stand.
 */
final class SimBossLeftClick1114 {

    static final String NAME = "1114-boss-left-click-terminal";
    private static final String STATIONS = "com.killer560.hub.bosssim.BossStations";

    private SimBossLeftClick1114() {
    }

    private static String title(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "" : McCompat.screen(mc).getTitle().getString());
    }

    private static void close(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(3);
    }

    /** Stands beside S1's terminal and sends an attack on its stand; false when no stand was found. */
    private static boolean leftClick(ClientGameTestContext ctx, String label) {
        double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                new Object[]{1, label});
        SimBossTests.tp(ctx, Math.floor(at[0]) - 1.5, at[1], Math.floor(at[2]) + 0.5, 0, 0);
        ctx.waitTicks(6);
        return ctx.computeOnClient(mc -> {
            Entity stand = SimBossTests.standAt(mc, at, "Inactive Terminal");
            if (stand == null) {
                return false;
            }
            mc.gameMode.attack(mc.player, stand);
            return true;
        });
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S1", true);
        expect(f, "a stand to left click", leftClick(ctx, "T1"), true);
        boolean opened = until(ctx, "left click opens T1", mc -> !title(ctx).isEmpty(), 40);
        println("boss", "left click opened '" + title(ctx) + "'");
        expect(f, "a left click on a terminal opens its window", opened, true);
        close(ctx);
        expect(f, "a stand for the right click", SimBossTests.clickTerminal(ctx, 1, "T2", "Inactive Terminal"), true);
        expect(f, "a right click still opens its window", until(ctx, "right click opens T2", mc -> !title(ctx).isEmpty(), 40), true);
        close(ctx);
    }
}
