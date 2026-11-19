package dev.testkit.gametest;

import dev.testkit.harness.ForeignTermFilter;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
 * orderonly-1119 (killer560 2026-11-19: a friend's Auto Terminals never clicked the sim's "Click in order!" board while every
 * other terminal worked; his mods include Odin 0.4.0 and NoFrills, both with terminal solvers that cancel clicks). Each case
 * turns on a test-side stand-in of one of those click filters ({@link ForeignTermFilter}, ported from their public source),
 * opens every sim terminal type in turn on S1 T1 through the sim's own open path, and requires Auto Terminals (with the
 * friend's terminal settings) to SOLVE each one through the filter: the sim's solved count, and the sim's own count of the
 * clicks that arrived.
 * <ul>
 * <li>{@code 1119-boss-term-odin-filter}: Odin's Terminal Solver (solution only from slot packets, re-sends allowed clicks).</li>
 * <li>{@code 1119-boss-term-nofrills-order}: NoFrills with only its In Order solver on (every NoFrills type is off by default).</li>
 * <li>{@code 1119-boss-term-nofrills-all}: NoFrills with every type on.</li>
 * </ul>
 */
final class SimBossTermForeign1119 {

    static final String ODIN = "1119-boss-term-odin-filter";
    static final String NF_ORDER = "1119-boss-term-nofrills-order";
    static final String NF_ALL = "1119-boss-term-nofrills-all";
    private static final String TERMS = "com.killer560.hub.bosssim.BossTerminals";
    private static final String STATIONS = "com.killer560.hub.bosssim.BossStations";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";
    private static final String[] TYPES = {"NUMBERS", "PANES", "RUBIX", "STARTS_WITH", "SELECT", "MELODY"};

    private SimBossTermForeign1119() {
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
            // The friend's terminal settings (his bug report, 2026-11-19 00:07).
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
        ForeignTermFilter.nofrillsOn = name.equals(NF_ORDER) ? Set.of("InOrder")
                : Set.of("Panes", "InOrder", "StartsWith", "Select", "Colors");
        ForeignTermFilter.enable(name.equals(ODIN) ? ForeignTermFilter.Mode.ODIN : ForeignTermFilter.Mode.NOFRILLS);
        List<String> failed = new ArrayList<>();
        try {
            for (String type : TYPES) {
                ForeignTermFilter.resetCounts();
                int solvedBefore = (Integer) call(TERMS, "solvedCount");
                int openedBefore = (Integer) call(TERMS, "openedCount");
                SimBossTests.serverRun(ctx, s -> open(s, type));
                if (!until(ctx, type + " opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                    f.add(type + ": never opened");
                    continue;
                }
                long openedAt = System.currentTimeMillis();
                int[] sim = {0};
                boolean solved = until(ctx, type + " solved", mc -> {
                    int c = simClicks(mc.getSingleplayerServer());
                    if (c >= 0) {
                        sim[0] = c;
                    }
                    return (Integer) call(TERMS, "solvedCount") > solvedBefore;
                }, type.equals("MELODY") ? 900 : 300);
                long ms = System.currentTimeMillis() - openedAt;
                println(name, String.format(Locale.ROOT, "%s solved=%s in %d ms, sim received %d click(s), filter %s",
                        type, solved, ms, sim[0], ForeignTermFilter.counts()));
                if (!solved) {
                    failed.add(type);
                    ctx.runOnClient(mc -> {
                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.player.closeContainer();
                        }
                    });
                    ctx.waitTicks(5);
                }
                ctx.waitTicks(10);
            }
        } finally {
            ForeignTermFilter.mode = ForeignTermFilter.Mode.OFF;
        }
        println(name, "unsolved types: " + (failed.isEmpty() ? "none" : String.join(", ", failed)));
        expect(f, "terminal types Auto Terminals could not solve through the filter", failed.isEmpty() ? "none" : String.join(", ", failed), "none");
    }

    /** The sim's own open path for one type on S1 T1 (what a stand click runs, with the type chosen). */
    private static void open(MinecraftServer s, String type) {
        try {
            ServerPlayer sp = s.getPlayerList().getPlayers().get(0);
            Class<?> stations = Class.forName(STATIONS);
            Object station = null;
            for (Object st : (List<?>) stations.getMethod("all").invoke(null)) {
                var rec = st.getClass();
                if ((Integer) rec.getMethod("section").invoke(st) == 1 && "T1".equals(rec.getMethod("label").invoke(st))) {
                    station = st;
                }
            }
            Class<?> terms = Class.forName(TERMS);
            Class<?> typeCls = Class.forName(TERMS + "$Type");
            Object t = typeCls.getMethod("valueOf", String.class).invoke(null, type);
            var m = terms.getDeclaredMethod("open", ServerPlayer.class, station.getClass(), typeCls);
            m.setAccessible(true);
            m.invoke(null, sp, station, t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not open a " + type + " board", e);
        }
    }

    private static int simClicks(MinecraftServer s) {
        try {
            Object menu = s.getPlayerList().getPlayers().get(0).containerMenu;
            Object term = SimBossTermNumbers1119.field(menu, "term");
            return term == null ? -1 : (Integer) SimBossTermNumbers1119.field(term, "clicks");
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
