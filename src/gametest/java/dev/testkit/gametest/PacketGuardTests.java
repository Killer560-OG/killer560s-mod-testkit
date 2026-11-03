package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.reflect.Method;

/**
 * The mod's packet guard (2026-11-02, noammcrash-1102): a throw out of killer560smod's own code on the packet path is
 * logged and skipped instead of disconnecting ("Network Protocol Error"), and anyone else's throw still disconnects.
 *
 * <p>996 arms the mod's dev-only test throw (its own hook on the time packet, after ensureRunningOnSameThread, the shape
 * of every real hook) and proves, on a real server, that the throws HAPPENED (the guard's kept count rises by exactly
 * the armed number) and that the client is still connected afterwards. The control half runs the throw-site rule on
 * synthetic traces: ours (a com.killer560 frame, or a killer560smod$ mixin handler, under JDK frames) is kept;
 * vanilla's and another mod's handler (NoammAddons' shape) are not.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PacketGuardTests implements FabricClientGameTest {

    private static final int ARMED = 3;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String name = "996-packet-guard";
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(0, 100, 0, 8)
                        .catchFloor(80)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    try {
                        Class<?> guard = Class.forName("com.killer560.hub.packetguard.PacketGuard");
                        boolean dev = Class.forName("com.killer560.hub.BuildVariant").getField("DEV_TOOLS").getBoolean(null);
                        throwSiteControl(guard, scenario);
                        if (!dev) {
                            scenario.log("RESULT release jar: test throw folded out, only the throw-site control ran");
                            return;
                        }
                        ctx.waitTicks(20);
                        boolean[] connected = new boolean[1];
                        ctx.runOnClient(mc -> connected[0] = mc.level != null && mc.getConnection() != null);
                        if (!connected[0]) {
                            throw new AssertionError("not connected before the test started");
                        }
                        int before = (int) guard.getMethod("kept").invoke(null);
                        guard.getMethod("armTestThrow", int.class).invoke(null, ARMED);
                        int kept = before;
                        for (int i = 0; i < 40 && kept - before < ARMED; i++) {
                            // The server sends a time packet every 20 ticks; /time set forces one now.
                            server.command("time set " + (1000 + i * 100));
                            ctx.waitTicks(10);
                            kept = (int) guard.getMethod("kept").invoke(null);
                        }
                        ctx.waitTicks(20);
                        ctx.runOnClient(mc -> connected[0] = mc.level != null && mc.getConnection() != null);
                        scenario.log("  kept before=" + before + " after=" + kept + " connected=" + connected[0]);
                        if (kept - before != ARMED) {
                            throw new AssertionError("the armed throws did not all run through the guard: kept "
                                    + (kept - before) + " of " + ARMED + " (vacuous otherwise)");
                        }
                        if (!connected[0]) {
                            throw new AssertionError("disconnected by a throw from the mod's own packet hook");
                        }
                        scenario.log("RESULT " + ARMED + " throws from the mod's packet hook kept, still connected");
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("PacketGuard not reachable: " + e, e);
                    }
                });
    }

    private static void throwSiteControl(Class<?> guard, Scenario scenario) throws ReflectiveOperationException {
        Method site = guard.getDeclaredMethod("ourThrowSite", Throwable.class);
        site.setAccessible(true);
        check(site, scenario, "ours under JDK frames", true,
                frame("java.lang.Integer", "parseInt"), frame("com.killer560.hub.x.Feature", "onChat"),
                frame("net.minecraft.client.multiplayer.ClientPacketListener", "handleSystemChat"));
        check(site, scenario, "our mixin handler", true,
                frame("java.util.HashMap", "get"),
                frame("net.minecraft.client.multiplayer.ClientPacketListener", "handler$zza000$killer560smod$onX"));
        check(site, scenario, "vanilla", false,
                frame("net.minecraft.world.entity.Entity", "setId"),
                frame("net.minecraft.client.multiplayer.ClientPacketListener", "handler$zza000$killer560smod$wrap"));
        check(site, scenario, "another mod's handler", false,
                frame("java.util.ArrayList", "get"),
                frame("net.minecraft.client.multiplayer.ClientPacketListener", "handler$zzb000$noammaddons$onPacket"),
                frame("com.killer560.hub.x.Feature", "later"));
    }

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 1);
    }

    private static void check(Method site, Scenario scenario, String what, boolean expectOurs,
                              StackTraceElement... trace) throws ReflectiveOperationException {
        RuntimeException e = new RuntimeException("synthetic");
        e.setStackTrace(trace);
        boolean ours = site.invoke(null, new IllegalStateException("wrapper", e)) != null;
        scenario.log("  throw site " + what + ": ours=" + ours);
        if (ours != expectOurs) {
            throw new AssertionError("throw-site rule wrong for " + what + ": ours=" + ours);
        }
    }
}
