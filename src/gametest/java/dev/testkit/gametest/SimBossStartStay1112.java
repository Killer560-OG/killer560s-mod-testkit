package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1112-boss-start-stay} (simstartstay-1112, killer560 2026-11-12: "running /start s2 shouldnt move me just reload
 * everything there"). Inside a running F7 boss fight, {@code /simboss s2} resets the fight and the arena as before but moves
 * nobody: he is stood away from S2's own spot with a set look, a station is done, the command runs, and afterwards his
 * position and look are what they were, the station is not done and the fight runs at section 2.
 */
final class SimBossStartStay1112 {

    static final String NAME = "1112-boss-start-stay";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final String FIGHT = B + "BossFight";
    private static final String STATIONS = B + "BossStations";
    private static final String SIM = B + "BossSim";

    private SimBossStartStay1112() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S2", true);
        Vec3 s1 = ctx.computeOnClient(mc -> mc.player.position());
        SimBossTests.tp(ctx, s1.x + 3.0, s1.y, s1.z, 77.0f, 12.0f);
        serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, 2, "T1"}));
        ctx.waitTicks(5);
        String before = String.valueOf(call(STATIONS, "describe"));
        expect(f, "T1 done before the command (control)", before.contains("S2 T1=true"), true);
        Vec3 p0 = ctx.computeOnClient(mc -> mc.player.position());
        float[] look0 = ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("simboss s2"));
        boolean reset = until(ctx, "the fight reset at S2", mc -> !(Boolean) call(SIM, "isBuilding")
                && (Boolean) call(FIGHT, "running") && String.valueOf(call(STATIONS, "describe")).contains("S2 T1=false"), 2400);
        expect(f, "the command reset the fight (T1 undone, running again)", reset, true);
        ctx.waitTicks(20);
        Vec3 p1 = ctx.computeOnClient(mc -> mc.player.position());
        float[] look1 = ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
        println("boss", "before " + p0 + " " + look0[0] + "/" + look0[1] + ", after " + p1 + " " + look1[0] + "/" + look1[1]);
        expect(f, "section after /simboss s2", call(FIGHT, "section"), 2);
        if (Math.abs(p1.x - p0.x) > 0.1 || Math.abs(p1.z - p0.z) > 0.1 || Math.abs(p1.y - p0.y) > 0.6) {
            f.add("the player moved: " + p0 + " -> " + p1);
        }
        if (Math.abs(look1[0] - look0[0]) > 0.5 || Math.abs(look1[1] - look0[1]) > 0.5) {
            f.add("the look changed: " + look0[0] + "/" + look0[1] + " -> " + look1[0] + "/" + look1[1]);
        }
    }
}
