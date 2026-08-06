package dev.testkit.gametest;

import dev.testkit.harness.ChatWatch;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Runs a scenario against a <b>real dedicated server with a real anticheat on it</b>, and fails the
 * build if any check flags.
 *
 * <pre>{@code
 * public class MyTest implements FabricClientGameTest {
 *     public void runTest(ClientGameTestContext ctx) {
 *         Scenario.run(ctx, "my-thing",
 *             (server, s) -> TestMap.on(server)
 *                     .platform(0, 150, 0, 12)
 *                     .catchFloor(140)
 *                     .bridgeGap(1, 40)
 *                     .survival()
 *                     .give("white_wool", 64)
 *                     .spawn(-3.5, 0.5, -90f)
 *                     .build(),
 *             (server, s) -> {
 *                 ctx.getInput().holdKey(o -> o.keyUp);
 *                 ctx.waitTicks(200);
 *                 ctx.getInput().releaseKey(o -> o.keyUp);
 *             });
 *     }
 * }
 * }</pre>
 *
 * <p>Register the class in {@code src/gametest/resources/fabric.mod.json} under
 * {@code fabric-client-gametest}, then {@code ./gradlew runClientGameTest -Pscenario=my-thing}.
 *
 * <h2>What a scenario gets</h2>
 * A dedicated server process on a freshly deleted world, the client connected, the anticheat's verbose
 * channel reporting to the server console, and — after the body — an assertion that it said nothing. An
 * empty flag list is the only result that earns the words "does not flag".
 *
 * <h2>The harness proves itself</h2>
 * A suite whose job is reporting flags fails silently when it breaks: no anticheat and no flags look
 * identical from outside. {@link #assertDetectorWorks()} cheats deliberately and requires the anticheat
 * to notice. Call it in any scenario whose clean result you intend to quote.
 */
public final class Scenario {

    /** Shapes an anticheat's verbose and alert lines take. */
    private static final String[] FLAG_MARKERS = {"failed", "[grim]", "grim »", "violation", " vl", "verbose"};
    /** Looks like a flag, is not: enable confirmations, join noise, our own echoes. */
    private static final String[] FLAG_EXEMPT = {"verbose enabled", "verbose is now", "alerts enabled",
            "alerts is now", "now receiving", "no longer receiving", "grimac", "logged in",
            "joined the game", "left the game", "made the advancement", "[testkit]"};

    private final ClientGameTestContext ctx;
    private final String name;
    private TestServer server;

    private Scenario(ClientGameTestContext ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    /** Set by {@code -Pnogrim}: run the bodies with the anticheat muted, to isolate a failure. */
    public static boolean anticheatMuted() {
        return Boolean.getBoolean("testkit.nogrim");
    }

    /** Honour {@code -Pscenario=…}. */
    public static boolean skip(String name) {
        String filter = System.getProperty("testkit.scenario", "");
        return !filter.isEmpty() && !name.contains(filter);
    }

    /**
     * Start the server, connect, arm the anticheat, build the world, run the body, then assert that
     * nothing flagged.
     */
    public static void run(ClientGameTestContext ctx, String name,
                           BiConsumer<TestServer, Scenario> build,
                           BiConsumer<TestServer, Scenario> body) {
        if (skip(name)) {
            return;
        }
        Scenario scenario = new Scenario(ctx, name);
        try (TestServer server = TestServer.start()) {
            scenario.server = server;
            scenario.connect();
            scenario.arm(server);
            // Every scenario starts in an empty world. Leftover entities from an earlier run wander into
            // whatever is being measured, and a fake player is a real player in the world save.
            server.command("testkit sweep");
            ctx.waitTicks(10);
            build.accept(server, scenario);
            ctx.waitTicks(60);          // let the world settle before anything is measured
            server.mark();
            ChatWatch.clear();
            body.accept(server, scenario);
            ctx.waitTicks(20);          // let a late verbose line land before looking
            scenario.assertClean();
        } finally {
            scenario.disconnect();
        }
    }

    /** Like {@link #run}, but for a scenario meant to flag: the body decides the verdict. */
    public static void runExpectingFlags(ClientGameTestContext ctx, String name,
                                         BiConsumer<TestServer, Scenario> build,
                                         BiConsumer<TestServer, Scenario> body) {
        if (skip(name)) {
            return;
        }
        Scenario scenario = new Scenario(ctx, name);
        try (TestServer server = TestServer.start()) {
            scenario.server = server;
            scenario.connect();
            scenario.arm(server);
            server.command("testkit sweep");
            ctx.waitTicks(10);
            build.accept(server, scenario);
            ctx.waitTicks(60);
            server.mark();
            ChatWatch.clear();
            body.accept(server, scenario);
        } finally {
            scenario.disconnect();
        }
    }

    private void connect() {
        ctx.runOnClient(mc -> {
            ServerData data = new ServerData("testkit", TestServer.address(), ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc,
                    ServerAddress.parseString(TestServer.address()), data, false, null);
        });
        ctx.waitFor(mc -> mc.player != null && mc.level != null, 1200);
        // A scenario run opens a window and plays twenty minutes of Minecraft at whatever the machine's
        // volume happens to be.
        ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(
                net.minecraft.sounds.SoundSource.MASTER).set(0.0));
        ctx.waitTicks(40);
        System.out.println("[" + name + "] connected as "
                + ctx.computeOnClient(mc -> mc.player.getGameProfile().name()));
    }

    private void disconnect() {
        ctx.runOnClient(mc -> {
            if (mc.level != null) {
                mc.disconnectWithProgressScreen();
            }
        });
        ctx.waitFor(mc -> mc.level == null, 600);
        ctx.waitTicks(20);
        ctx.setScreen(TitleScreen::new);
        ctx.waitTicks(10);
    }

    /**
     * Confirm the anticheat is running and reporting.
     *
     * <p>The test player is deliberately <b>not</b> opped. GrimAC treats operators as exempt, so an opped
     * client produces a spotless run for the worst possible reason — that was the first version of this
     * harness and its positive control caught it. Verbose goes to the server console instead, which needs
     * no permissions at all.
     */
    private void arm(TestServer server) {
        if (anticheatMuted()) {
            System.out.println("[" + name + "] anticheat muted by -Pnogrim");
            return;
        }
        if (!server.logged("GrimAC")) {
            throw new AssertionError("The anticheat never announced itself on the test server, so every "
                    + "'clean' result would be meaningless. Run ./gradlew prepareTestServer and check "
                    + "build/testserver-console.log.");
        }
        server.mark();
    }

    /** Check output produced since the last {@link TestServer#mark()}, read from the server console. */
    public List<String> flags() {
        List<String> out = new ArrayList<>();
        for (String line : server.since()) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean marked = false;
            for (String marker : FLAG_MARKERS) {
                if (lower.contains(marker)) {
                    marked = true;
                    break;
                }
            }
            if (!marked) {
                continue;
            }
            boolean exempt = false;
            for (String skip : FLAG_EXEMPT) {
                if (lower.contains(skip)) {
                    exempt = true;
                    break;
                }
            }
            if (!exempt) {
                out.add(line);
            }
        }
        return out;
    }

    private void assertClean() {
        List<String> flags = flags();
        if (flags.isEmpty()) {
            System.out.println("[" + name + "] anticheat clean — no verbose lines");
            return;
        }
        StringBuilder message = new StringBuilder("[" + name + "] anticheat flagged "
                + flags.size() + " time(s):");
        for (String flag : flags) {
            message.append(System.lineSeparator()).append("    ").append(flag);
        }
        System.out.println(message);
        throw new AssertionError(message.toString());
    }

    /**
     * Move the player somewhere no legitimate client could reach and require the anticheat to notice.
     *
     * <p>This is the positive control and it is not optional. Without it, a harness that has lost its
     * anticheat — a bad classpath, a version bump, a permission change — reports every scenario as clean
     * and every module as safe. The failure mode of a silent detector is that it passes everything.
     */
    public void assertDetectorWorks() {
        if (anticheatMuted()) {
            return;
        }
        server.mark();
        ctx.runOnClient(mc -> mc.player.setPos(mc.player.getX() + 6.0, mc.player.getY(), mc.player.getZ()));
        ctx.waitTicks(60);
        List<String> flags = flags();
        if (flags.isEmpty()) {
            throw new AssertionError("Positive control FAILED: teleported six blocks sideways and the "
                    + "anticheat said nothing. Every 'clean' result below this is meaningless.");
        }
        System.out.println("[" + name + "] positive control OK — detector saw: " + flags.get(0));
        server.mark();
    }

    /** Dump every entity and where it is relative to the client — the first question when a run is odd. */
    public void reportEntities() {
        server.command("testkit report");
        ctx.waitTicks(10);
    }

    /** Where the client is right now: {@code {x, y, z}}. */
    public double[] playerPosition() {
        return ctx.computeOnClient(mc ->
                new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
    }

    /** Print a labelled line into the run output, so a trace reads in order with the harness's own. */
    public void log(String message) {
        System.out.println("[" + name + "] " + message);
    }

    public String name() {
        return name;
    }

    public TestServer server() {
        return server;
    }

    public ClientGameTestContext ctx() {
        return ctx;
    }
}
