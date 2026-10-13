package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 995-reboot-not-prism: the mod's /reboot (reboot-cmd, 2026-10-13) in a test client connected to the dedicated server.
 * This client is started by gradle's java, not by Prism, so every way in must say so and leave the game running: the
 * command is registered on the client dispatcher; typing {@code /reboot} shows "not started by Prism Launcher";
 * {@code /reboot confirm} with nothing pending says so; the menu's Home page has a Reboot button (with a tooltip) whose
 * real click shows the same refusal. After each, the game is still running and no relauncher was started (its
 * reboot.log never appears or grows). Never starts Prism.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class RebootCommandTest implements FabricClientGameTest {

    private static final String FEAT = "reboot.RebootFeature";

    private static String last() {
        return (String) Mod.staticCall(FEAT, "lastMessage");
    }

    private static long relauncherLogSize() throws Exception {
        Path log = ((Path) Mod.staticCall(FEAT, "workDir")).resolve("reboot.log");
        return Files.exists(log) ? Files.size(log) : -1;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "995-reboot-not-prism",
                (server, scenario) -> TestMap.on(server)
                        .platform(8)
                        .catchFloor(140)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    try {
                        body(ctx, scenario);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

    private static void body(ClientGameTestContext ctx, Scenario scenario) throws Exception {
        LogTap.install();
        long mark = LogTap.mark();
        long logBefore = relauncherLogSize();

        boolean registered = ctx.computeOnClient(mc -> net.fabricmc.fabric.impl.command.client.ClientCommandInternals
                .getActiveDispatcher().getRoot().getChild("reboot") != null);
        if (!registered) {
            throw new AssertionError("/reboot is not registered on the client command dispatcher");
        }
        boolean connected = ctx.computeOnClient(mc -> mc.player != null && mc.getCurrentServer() != null);
        scenario.log("connected to a server (a dungeon would need confirmation): " + connected);

        // 1. /reboot typed.
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reboot"));
        ctx.waitTicks(10);
        String typed = last();
        scenario.log("/reboot said: " + typed);
        if (!typed.contains("this game was not started by Prism Launcher")) {
            throw new AssertionError("/reboot did not say the game was not started by Prism: '" + typed + "'");
        }
        List<String> lines = LogTap.since(mark);
        boolean inChat = lines.stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("Can't reboot"));
        if (!inChat) {
            throw new AssertionError("the refusal never reached chat (" + lines.size() + " log lines since the command)");
        }
        assertStillRunning(ctx, "/reboot");

        // 2. /reboot confirm with nothing pending.
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reboot confirm"));
        ctx.waitTicks(10);
        String confirm = last();
        scenario.log("/reboot confirm said: " + confirm);
        if (!confirm.startsWith("Nothing to confirm")) {
            throw new AssertionError("/reboot confirm with nothing pending said '" + confirm + "'");
        }
        assertStillRunning(ctx, "/reboot confirm");

        // 3. Home's Reboot button, a real click.
        String tip = ctx.computeOnClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describeScoped",
                null, "home button", "Reboot"));
        scenario.log("Reboot button tooltip: " + tip);
        if (tip == null || !tip.contains("Prism")) {
            throw new AssertionError("Home's Reboot button has no tooltip naming Prism: " + tip);
        }
        Screen[] s = new Screen[1];
        ctx.runOnClient(mc -> {
            try {
                s[0] = MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.call(s[0], "selectCategory", "Home");
        });
        ctx.waitTicks(3);
        String pressed = ctx.computeOnClient(mc -> {
            int[] b = (int[]) Mod.call(s[0], "homeButtonBounds", "Reboot");
            if (b == null) {
                return "Home has no Reboot button";
            }
            MenuACases.press(s[0], b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
            return null;
        });
        if (pressed != null) {
            throw new AssertionError(pressed);
        }
        ctx.waitTicks(10);
        String button = last();
        scenario.log("Home's Reboot button said: " + button);
        if (!button.contains("this game was not started by Prism Launcher")) {
            throw new AssertionError("Home's Reboot button did not give the not-Prism refusal: '" + button + "'");
        }
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        assertStillRunning(ctx, "the Home button");

        long logAfter = relauncherLogSize();
        if (logAfter != logBefore) {
            throw new AssertionError("a relauncher ran: reboot.log went from " + logBefore + " to " + logAfter + " bytes");
        }
        scenario.log("995 PASS: three refusals, the game kept running, no relauncher started");
    }

    private static void assertStillRunning(ClientGameTestContext ctx, String after) {
        ctx.waitTicks(20);
        boolean running = ctx.computeOnClient(mc -> mc.isRunning() && mc.player != null);
        if (!running) {
            throw new AssertionError("the game is closing (or left the server) after " + after);
        }
    }
}
