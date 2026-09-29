package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

/**
 * Can another player's chat message disconnect him?
 *
 * <p>Found 2026-09-29: the Simon Says party-progress tracker matched {@code SS (\d+)/(\d+)} with unbounded
 * digits and fed both groups to {@code Integer.parseInt}, from a listener on the RAW
 * {@code ClientReceiveMessageEvents}. Minecraft treats a throw out of a packet listener as a packet error and
 * disconnects - {@code ClientCommonPacketListenerImpl.onPacketError} logs "Failed to handle packet,
 * disconnecting". So anyone typing {@code SS 99999999999/5} in party, guild or all chat dropped him out of
 * Hypixel mid-run, and the tracker that reads it is on by default.
 *
 * <p>That is worth a scenario of its own rather than a unit test, because the thing being asserted is not
 * "the parse returns something sensible" - it is "the client is still connected afterwards", which only a
 * real client on a real server can answer.
 *
 * <p>The lines below are a list rather than one string, so this grows into a general hostile-chat probe: any
 * future parser that another player can feed belongs here.
 */
public class HostileChatTests implements FabricClientGameTest {

    /** Each one is shaped to look like something a mod here parses, with a value no parser should trust. */
    private static final List<String> HOSTILE = List.of(
            "SS 99999999999/5",
            "SS 5/99999999999",
            "SS 0000000000000000009/2",
            "Bob: SS 2147483648/7",
            "SS -1/-1",
            "SS 1/0");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String name = "86-hostile-chat";
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
                    ctx.waitTicks(20);
                    boolean[] connected = new boolean[1];
                    ctx.runOnClient(mc -> connected[0] = mc.level != null);
                    if (!connected[0]) {
                        throw new AssertionError("not connected before the test even started");
                    }
                    for (String line : HOSTILE) {
                        scenario.log("  broadcasting: " + line);
                        // Through the server, so it arrives as a real chat packet on the real listener path -
                        // which is the whole point. Calling the handler directly would prove nothing about
                        // what Minecraft does with a throw.
                        server.command("say " + line);
                        ctx.waitTicks(10);
                        ctx.runOnClient(mc -> connected[0] = mc.level != null);
                        if (!connected[0]) {
                            throw new AssertionError("the client was disconnected by a chat line any player "
                                    + "can type: \"" + line + "\"");
                        }
                    }
                    ctx.waitTicks(20);
                    ctx.runOnClient(mc -> connected[0] = mc.level != null);
                    if (!connected[0]) {
                        throw new AssertionError("the client disconnected shortly after the hostile lines");
                    }
                    scenario.log("RESULT survived " + HOSTILE.size()
                            + " hostile chat line(s) and stayed connected");
                });
    }
}
