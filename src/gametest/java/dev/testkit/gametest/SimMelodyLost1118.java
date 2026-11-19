package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.tp;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1118-boss-melodylost-sim} (melodylost-1118, killer560's own numbers): in the boss sim's S1, six of seven objectives
 * are done, then 4.5 s later (90 ticks of the sim clock) the player's own REAL Melody terminal (the sim's Melody menu,
 * opened with a real click on the stand) completes the seventh. Own-menu evidence is read from the open menu, nothing is
 * injected: Time Lost To Melody must read 4.50 s for S1 (the wall time the test measured between the two lines, within
 * a tick or two), decided by "own menu". A second round has a normal terminal (Panes) last: 0.
 */
final class SimMelodyLost1118 {

    static final String NAME = "1118-boss-melodylost-sim";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final String STATIONS = B + "BossStations";
    private static final String TERMS = B + "BossTerminals";
    private static final String CFG = "com.killer560.hub.splittimers.TerminalTimersConfig";
    private static final String ML = "com.killer560.hub.splittimers.MelodyLost";

    private SimMelodyLost1118() {
    }

    private static void complete(ClientGameTestContext ctx, int section, String label) {
        serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, section, label}));
        ctx.waitTicks(2);
    }

    private static long lostMs(ClientGameTestContext ctx, int section) {
        return (Long) ctx.computeOnClient(mc -> ModUnderTest.staticCall(ML, "lostMs", new Class<?>[]{int.class}, new Object[]{section}));
    }

    private static String decision(ClientGameTestContext ctx, int section) {
        Object d = ctx.computeOnClient(mc -> ModUnderTest.staticCall(ML, "decision", new Class<?>[]{int.class}, new Object[]{section}));
        return String.valueOf(d);
    }

    /** Six objectives of S1 done by the server (the sim's own lines), the last terminal T4 clicked after {@code gapTicks}. */
    private static void round(ClientGameTestContext ctx, List<String> f, String type, long wantMs, String wantWhy) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ML, "reset", new Class<?>[0], new Object[0]));
        for (String l : new String[]{"T1", "T2", "T3", "east lever", "west lever", "SS"}) {
            complete(ctx, 1, l);
        }
        long sixth = System.currentTimeMillis();
        double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class}, new Object[]{1, "T4"});
        System.setProperty("killer560.bosssim.terminal", type);
        tp(ctx, Math.floor(at[0]) - 2 + 0.5, at[1], Math.floor(at[2]) + 0.5, 0, 0);
        int openedBefore = (Integer) call(TERMS, "openedCount");
        ctx.waitTicks(4);
        ctx.runOnClient(mc -> {
            Entity stand = SimBossTests.standAt(mc, at, "Inactive Terminal");
            if (stand != null) {
                mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)), InteractionHand.MAIN_HAND);
            }
        });
        boolean opened = until(ctx, type + " menu opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 60);
        expect(f, type + " terminal opened by a real click", opened, true);
        // Hold the menu open until 4.5 s after the sixth line, then the seventh objective is done.
        long waitMs = sixth + 4500L - System.currentTimeMillis();
        for (int i = 0; i < 200 && System.currentTimeMillis() < sixth + 4500L - 25L; i++) {
            ctx.waitTicks(1);
        }
        complete(ctx, 1, "T4");
        long gap = System.currentTimeMillis() - sixth;
        long lost = lostMs(ctx, 1);
        String why = decision(ctx, 1);
        println(NAME, type + ": lost " + lost + " ms, " + why + ", wall gap " + gap + " ms (left to wait was " + waitMs + ")");
        if (wantMs > 0) {
            expect(f, type + " last: loss within 300 ms of the measured gap", Math.abs(lost - gap) <= 300L, true);
            expect(f, type + " last: loss about 4.5 s", lost >= 4200L && lost <= 5000L, true);
        } else {
            expect(f, type + " last: nothing lost", lost, 0L);
        }
        expect(f, type + " last: decided by " + wantWhy, why.contains(wantWhy), true);
        ctx.runOnClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        });
        System.clearProperty("killer560.bosssim.terminal");
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setMelodyLostPhase", true);
            ModUnderTest.set(cfg, "setMelodyLostSplits", true);
            ModUnderTest.set(cfg, "setMelodyLostHud", true);
        });
        try {
            start(ctx, "S1", true);
            round(ctx, f, "MELODY", 4500L, "own menu");
            // The same section again with a normal terminal last: a fresh fight, nothing counted.
            start(ctx, "S1", true);
            round(ctx, f, "PANES", 0L, "not a Melody");
        } finally {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(CFG);
                ModUnderTest.set(cfg, "setMelodyLostPhase", false);
                ModUnderTest.set(cfg, "setMelodyLostSplits", false);
                ModUnderTest.set(cfg, "setMelodyLostHud", false);
                ModUnderTest.set(cfg, "setEnabled", false);
            });
        }
    }
}
