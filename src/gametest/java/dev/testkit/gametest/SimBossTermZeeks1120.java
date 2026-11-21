package dev.testkit.gametest;

import dev.testkit.harness.ForeignTermFilter;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * zeeks-simterm-1120 (killer560 2026-11-20: the friend's sim terminals still fail on 4f4b2d8b0; his new report shows a
 * Select solved in 0.2 s, then a Rubix left unsolved after 4 s, then a Melody left at 33 %). Every terminal is opened right
 * after the one before it solved, the way a run goes (Select, Rubix, Melody first, as in his log, then five more Rubix
 * boards and the other types), with his terminal settings, and Auto Terminals must SOLVE each one.
 * <ul>
 * <li>{@code 1120-boss-term-zeeks-seq}: no other mod's click filter.</li>
 * <li>{@code 1120-boss-term-zeeks-seq-nf}: the NoFrills stand-in with its In Order and Colors solvers on.</li>
 * <li>{@code 1120-boss-term-zeeks-seq-odin}: the Odin stand-in.</li>
 * </ul>
 */
final class SimBossTermZeeks1120 {

    static final String SEQ = "1120-boss-term-zeeks-seq";
    static final String SEQ_NF = "1120-boss-term-zeeks-seq-nf";
    static final String SEQ_ODIN = "1120-boss-term-zeeks-seq-odin";
    private static final String TERMS = "com.killer560.hub.bosssim.BossTerminals";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";
    private static final String[] TYPES = {"SELECT", "RUBIX", "MELODY", "RUBIX", "RUBIX", "RUBIX", "RUBIX", "RUBIX",
            "NUMBERS", "PANES", "STARTS_WITH", "NUMBERS"};

    private SimBossTermZeeks1120() {
    }

    static void run(ClientGameTestContext ctx, List<String> f, String name) {
        start(ctx, "S1", true);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", true);
            for (String s : new String[]{"Panes", "Rubix", "Numbers", "StartsWith", "Select", "Melody"}) {
                ModUnderTest.set(cfg, "setAuto" + s + "Enabled", true);
                ModUnderTest.set(cfg, "set" + s + "Enabled", true);
            }
            // His terminal settings (bug reports 2026-11-19 00:07 and 2026-11-20 20:39, the same file).
            ModUnderTest.set(cfg, "setAutoClickMaxDelayMs", 120);
            ModUnderTest.set(cfg, "setAutoClickMinDelayMs", 90);
            ModUnderTest.set(cfg, "setFirstClickDelayMs", 0);
            ModUnderTest.set(cfg, "setBurstFinish", true);
            ModUnderTest.set(cfg, "setBurstSize", 5);
            ModUnderTest.set(cfg, "setBurstMode", false);
            ModUnderTest.set(cfg, "setAutoStreamSafe", false);
            ModUnderTest.set(cfg, "setQueueTerms", false);
            ModUnderTest.set(cfg, "setHoverTerminalsEnabled", false);
            ModUnderTest.set(cfg, "setHoldToClickEnabled", false);
            ModUnderTest.set(cfg, "setCustomGuiEnabled", true);
            ModUnderTest.set(cfg, "setHideMouseCursor", true);
        });
        ctx.waitTicks(5);
        if (name.equals(SEQ_NF)) {
            ForeignTermFilter.nofrillsOn = Set.of("InOrder", "Colors");
            ForeignTermFilter.enable(ForeignTermFilter.Mode.NOFRILLS);
        } else if (name.equals(SEQ_ODIN)) {
            ForeignTermFilter.enable(ForeignTermFilter.Mode.ODIN);
        }
        List<String> failed = new ArrayList<>();
        try {
            for (int i = 0; i < TYPES.length; i++) {
                String type = TYPES[i];
                String label = (i + 1) + ":" + type;
                ForeignTermFilter.resetCounts();
                int solvedBefore = (Integer) call(TERMS, "solvedCount");
                int openedBefore = (Integer) call(TERMS, "openedCount");
                SimBossTests.serverRun(ctx, s -> SimBossTermForeign1119.open(s, type));
                if (!until(ctx, label + " opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                    failed.add(label + " never opened");
                    continue;
                }
                long openedAt = System.currentTimeMillis();
                int[] sim = {0};
                boolean solved = until(ctx, label + " solved", mc -> {
                    int c = SimBossTermForeign1119.simClicks(mc.getSingleplayerServer());
                    if (c >= 0) {
                        sim[0] = c;
                    }
                    return (Integer) call(TERMS, "solvedCount") > solvedBefore;
                }, type.equals("MELODY") ? 900 : 200);
                long ms = System.currentTimeMillis() - openedAt;
                println(name, String.format(Locale.ROOT, "%s solved=%s in %d ms, sim received %d click(s), filter %s",
                        label, solved, ms, sim[0], ForeignTermFilter.counts()));
                if (!solved) {
                    failed.add(label);
                    ctx.runOnClient(mc -> {
                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.player.closeContainer();
                        }
                    });
                    ctx.waitTicks(5);
                } else if (sim[0] == 0 && !type.equals("MELODY")) {
                    failed.add(label + " solved with no click counted");
                }
                // The next terminal opens soon after the last one closed, as in a run.
                ctx.waitTicks(10);
            }
        } finally {
            ForeignTermFilter.mode = ForeignTermFilter.Mode.OFF;
        }
        println(name, "unsolved: " + (failed.isEmpty() ? "none" : String.join(", ", failed)));
        expect(f, "terminals Auto Terminals could not solve one after another", failed.isEmpty() ? "none" : String.join(", ", failed), "none");
    }
}
