package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1116-boss-start-cmd} (simstart-1116, killer560 2026-11-16: "/start s4 and such still arent working"; the command
 * was /simboss only, /start answered "Unknown or incomplete command"). For every phase word, the boss is started directly
 * (the menu's path) and the state noted: phase, section, position, station describe. Then the fight is stopped and the
 * same start is typed as {@code /start <word>} (or an alias form); the state must match the direct start, and the fight must
 * really have been rebuilt (the first look is a different start, so a command that did nothing fails).
 */
final class SimBossStart1116 {

    static final String NAME = "1116-boss-start-cmd";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final String FIGHT = B + "BossFight";
    private static final String STATIONS = B + "BossStations";
    private static final String SIM = B + "BossSim";
    /** command word, Start enum name it must equal */
    private static final String[][] CASES = {
            {"p1", "P1"}, {"maxor", "P1"}, {"p2", "P2"}, {"storm", "P2"}, {"s1", "S1"}, {"p3", "S1"}, {"goldor", "S1"},
            {"s2", "S2"}, {"s3", "S3"}, {"s4", "S4"}, {"S4", "S4"}, {"core", "CORE"}, {"p4", "P4"}, {"necron", "P4"},
            {"p5", "P5"}, {"dragons", "P5"}};

    private SimBossStart1116() {
    }

    private record State(String phase, int section, Vec3 pos, String stations) {
    }

    private static State state(ClientGameTestContext ctx) {
        Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
        return new State(String.valueOf(call(FIGHT, "phase")), (Integer) call(FIGHT, "section"), p,
                String.valueOf(call(STATIONS, "describe")));
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P1", true);
        String last = "P1";
        for (String[] c : CASES) {
            // Direct start (the menu's path) is the reference for what the command must reach.
            start(ctx, c[1], true);
            ctx.waitTicks(20);
            State want = state(ctx);
            // A different start in between so a command that does nothing cannot pass by standing on the same one.
            String other = c[1].equals("P1") ? "S4" : "P1";
            start(ctx, other, true);
            ctx.waitTicks(10);
            State between = state(ctx);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("simboss stop"));
            until(ctx, "the fight stopped", mc -> !(Boolean) call(FIGHT, "running"), 600);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("start " + c[0]));
            boolean built = until(ctx, "/start " + c[0] + " running", mc -> !(Boolean) call(SIM, "isBuilding")
                    && (Boolean) call(FIGHT, "running"), 2400);
            ctx.waitTicks(20);
            State got = state(ctx);
            String tag = "/start " + c[0] + " (" + c[1] + ")";
            println("boss", tag + " want " + want + " got " + got);
            expect(f, tag + ": the fight ran", built, true);
            expect(f, tag + ": phase", got.phase(), want.phase());
            expect(f, tag + ": section", got.section(), want.section());
            expect(f, tag + ": stations", got.stations(), want.stations());
            if (got.pos().distanceTo(want.pos()) > 1.5) {
                f.add(tag + ": position " + got.pos() + ", want " + want.pos());
            }
            if (got.pos().distanceTo(between.pos()) < 1.0 && between.pos().distanceTo(want.pos()) > 5.0) {
                f.add(tag + ": still at the previous start " + between.pos() + " (the command did nothing)");
            }
            last = c[1];
        }
        println("boss", String.format(Locale.ROOT, "%d command forms checked, last %s", CASES.length, last));
    }
}
