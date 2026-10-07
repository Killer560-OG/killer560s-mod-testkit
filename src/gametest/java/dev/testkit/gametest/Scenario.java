package dev.testkit.gametest;

import dev.testkit.harness.ChatWatch;
import dev.testkit.harness.Coverage;
import dev.testkit.harness.Disconnects;
import dev.testkit.harness.ModGate;
import dev.testkit.harness.Report;
import dev.testkit.harness.ScenarioList;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.SuiteVerdict;

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
 *
 * <h2>One failure does not end the run</h2>
 * A failing scenario is recorded, the client goes back to the title screen and the next test runs; the
 * build fails at the end with every failure listed under {@code [suite]}, and {@code -Pfailed} re-runs
 * just those. A scenario that starts and never reaches its verdict is a failure too — see
 * {@link SuiteVerdict}.
 */
public final class Scenario {

    /** Shapes an anticheat's verbose and alert lines take. */
    private static final String[] FLAG_MARKERS = {"failed", "[grim]", "grim »", "violation", " vl", "verbose"};
    /**
     * Looks like a flag, is not: enable confirmations, join noise, our own echoes.
     *
     * <p>{@code packetevents} is Grim's bundled packet library talking to itself — its update checker runs on
     * its own thread at some unfixed time after the server starts and logs {@code Failed to check for updates}
     * / {@code Failed to parse packetevents version!} when it cannot reach the network. That matches
     * {@code "failed"}, and because it races the measurement window it lands inside some scenario sooner or
     * later as a "flag" that names no check and no player. No check line carries the library's name, so this
     * exempts nothing real.
     *
     * <p>{@code [hx] } prefixes every line the Hx bridge in the companion mod prints (its startup line, one
     * {@code [hx] cmd /...} per player command, op errors). Those echo what a SCENARIO did - a stubbed
     * {@code /joininstance}, say - and a command can contain any word, "failed" included. GrimAC never prints that
     * prefix, so this exempts nothing the anticheat said.
     */
    private static final String[] FLAG_EXEMPT = {"verbose enabled", "verbose is now", "alerts enabled",
            "alerts is now", "now receiving", "no longer receiving", "grimac", "logged in",
            "joined the game", "left the game", "made the advancement", "[testkit]", "packetevents", "[hx] "};

    private final ClientGameTestContext ctx;
    private final String name;
    private TestServer server;

    private Scenario(ClientGameTestContext ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    /** Set by {@code -Pnogrim}: the server runs without the anticheat, to isolate a failure. */
    public static boolean anticheatMuted() {
        return Boolean.getBoolean("testkit.nogrim");
    }

    /**
     * Honour {@code -Pscenario=…}: a substring of the name, or several separated by commas, any of which
     * selects it. {@code -Pfailed} arrives here as the same comma list.
     *
     * <p>Public so a test that does not go through {@link #run} — a singleplayer check, say — can be
     * filtered the same way. A test that cannot be filtered out is a test every other one depends on.
     */
    /**
     * Present the test server to the client under a different address, while still dialling localhost.
     *
     * <p>Some mods gate their features on which server you are connected to - killer560s-mod refuses to run
     * its dungeon features unless {@code getCurrentServer().ip} contains {@code hypixel.net} or
     * {@code p3sim.net}, and several of them have no override for it at all. That check reads the stored
     * {@link ServerData}, which is separate from the address actually dialled, so a scenario can label the
     * connection and still connect locally.
     *
     * <p>It changes nothing about what the anticheat sees: same traffic, same world, same server. Cleared at
     * the end of every scenario so it cannot leak into the next one and quietly change what that measured.
     */
    public static void labelServerAs(String address) {
        serverLabel = address;
    }

    /** @see #labelServerAs */
    private static String serverLabel;

    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";

    /**
     * Copies his room database (killer560smod-roomdata, from the Mod Only Test instance) into this client and waits
     * until the mod has loaded it. Since mod 2026-10-05 the sim refuses to plan a floor before it has: room TYPES
     * (Entrance, Blood, Fairy, puzzles) come from it, and without them floors came out with no blood room. Offline,
     * an earlier attempt may be in its 30 s backoff, so this keeps asking for up to 1000 ticks.
     *
     * @return the number of files copied (0: none on this machine, nothing waited for)
     */
    public static int ensureRoomDatabase(ClientGameTestContext ctx) {
        int copied = 0;
        try {
            java.nio.file.Path source = java.nio.file.Path.of(ModUnderTest.instanceConfig(
                    Machine.roomsConfigDir(),
                    "killer560smod-roomdata"));
            if (java.nio.file.Files.isDirectory(source)) {
                java.nio.file.Path target = ModUnderTest.modConfig("killer560smod-roomdata");
                java.nio.file.Files.createDirectories(target);
                try (var files = java.nio.file.Files.list(source)) {
                    for (java.nio.file.Path f : files.toList()) {
                        if (java.nio.file.Files.isRegularFile(f)) {
                            java.nio.file.Files.copy(f, target.resolve(f.getFileName()),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            copied++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[room-database] copy failed: " + e);
        }
        if (copied == 0) {
            return 0;
        }
        ctx.runOnClient(mc -> loadRoomDatabaseNow());
        ctx.waitFor(mc -> {
            ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading");
            return (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady");
        }, 1000);
        return copied;
    }

    /**
     * Starts a room-database load NOW: clears the mod's failed-load backoff, then calls {@code ensureLoading}.
     *
     * <p>Any scenario on the test server with a dungeon sidebar runs the live map, which asks for the database,
     * and offline with no copy of it each failure backs off further (30, 60, 120 s ... up to 10 min). In a full
     * run 60/62/81-83 ran first and left it at 120 s, so the next sim scenario copied the files and then waited
     * 50 s on a load that would not even be tried for two minutes - 77 and 110 failed "Timed out waiting for
     * predicate" in ensureRoomDatabase (2026-10-07). The files this scenario just copied are the precondition
     * the backoff was waiting for, so the wait is over. Call once, on the client thread, after copying; calling it
     * every tick would defeat the backoff for a load that genuinely fails.
     */
    public static void loadRoomDatabaseNow() {
        String db = "com.killer560.hub.roomdatabase.RoomDatabase";
        if (!(Boolean) ModUnderTest.staticCall(db, "isReady")) {
            try {
                java.lang.reflect.Field next = Class.forName(db).getDeclaredField("nextAttemptAtMs");
                next.setAccessible(true);
                next.setLong(null, 0L);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not clear the room database's retry backoff", e);
            }
        }
        ModUnderTest.staticCall(db, "ensureLoading");
    }

    /**
     * How many sim builds have finished so far. Snapshot this BEFORE asking for a floor.
     *
     * @see #awaitSimBuild
     */
    public static long simBuildCount(ClientGameTestContext ctx) {
        long[] out = new long[1];
        ctx.runOnClient(mc -> out[0] = (Long) ModUnderTest.staticCall(BUILD_QUEUE, "buildsFinished"));
        return out[0];
    }

    /**
     * Waits for the sim build that was asked for AFTER {@code before} was taken to run to completion.
     *
     * <p>Every sim scenario used to do this instead:
     *
     * <pre>{@code ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy")); }</pre>
     *
     * <p>which is not a wait at all. {@code generate()} opens the world and the rooms are only queued from a
     * later server task, so at the moment that predicate first runs the queue is still empty and it returns
     * immediately - the scenario then measures a world with nothing in it. Scenario 76 reported "found 0
     * chest(s) in the built floor" three runs running on 2026-09-29 while the client log, one second later,
     * said "Sim build: 21 room(s) queued". Eleven scenarios shared the bug.
     *
     * <p>{@code isBusy()} cannot fix it on its own, because false means both "not started" and "finished".
     * {@code SimBuildQueue.buildsFinished()} only counts completions and only goes up, so it can.
     *
     * @param before the value {@link #simBuildCount} returned before the build was requested
     */
    public static void awaitSimBuild(ClientGameTestContext ctx, long before) {
        // Generous, and bounded: a 36-cell F7 paste is a few million blocks and takes tens of seconds in a
        // gametest client, but an unbounded wait would leave a frozen Minecraft window on his desktop.
        ctx.waitFor(mc -> (Long) ModUnderTest.staticCall(BUILD_QUEUE, "buildsFinished") > before, 24_000);
        ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"), 1200);
    }

    /**
     * A selected scenario that cannot run on this machine (no room captures, no mod checkout...): one SKIPPED line and a
     * SKIP row in the report saying why, instead of a RAN row whose reason is only in the log. Call it and return.
     */
    public static void skipped(String name, String why) {
        String reason = why;
        if (Machine.prismInstances() == null && why.contains("room")) {
            reason = why + " - " + Machine.CAPTURES_HINT;
        }
        System.out.println("[" + name + "] SKIPPED - " + reason);
        SuiteVerdict.finished(name);
        Report.caseFinished(name, "SKIP", "", reason, List.of(), List.of());
    }

    public static boolean skip(String name) {
        String filter = System.getProperty("testkit.scenario", "");
        if (ScenarioList.active()) {
            // List mode (-PlistScenarios): say whether the filter selects this name, and skip it either way.
            boolean selected = filter.isBlank();
            for (String part : filter.split(",")) {
                if (!part.isBlank() && name.contains(part.trim())) {
                    selected = true;
                }
            }
            ScenarioList.record(name, selected ? "all" : "no", "scenario");
            return true;
        }
        boolean selected = filter.isBlank();
        for (String part : filter.split(",")) {
            if (!part.isBlank() && name.contains(part.trim())) {
                selected = true;
            }
        }
        if (!selected) {
            return true;
        }
        // A @RequiresMod class whose mod is not loaded: a SKIP row with the reason, and nothing run.
        if (ModGate.missing() != null) {
            ModGate.skip(name);
            return true;
        }
        // Remembered so a failure from here on is filed under this name, which is what -Pfailed feeds back in as
        // the filter.
        SuiteVerdict.started(name);
        return false;
    }

    /**
     * Start the server, connect, arm the anticheat, build the world, run the body, then assert that
     * nothing flagged.
     */
    public static void run(ClientGameTestContext ctx, String name,
                           BiConsumer<TestServer, Scenario> build,
                           BiConsumer<TestServer, Scenario> body) {
        runInternal(ctx, name, build, body, false);
    }

    /**
     * Like {@link #run}, but for a scenario meant to flag: the body decides the verdict.
     *
     * <p>Use it for a module that departs from vanilla movement by design — one that snaps, nudges or
     * corrects the player on purpose draws a verbose line per correction by construction, and a scenario
     * that asserts silence can never pass at any tuning. Measure the lines instead (how many per action,
     * which check, what offset) and assert on that.
     */
    public static void runExpectingFlags(ClientGameTestContext ctx, String name,
                                         BiConsumer<TestServer, Scenario> build,
                                         BiConsumer<TestServer, Scenario> body) {
        runInternal(ctx, name, build, body, true);
    }

    private static void runInternal(ClientGameTestContext ctx, String name,
                                    BiConsumer<TestServer, Scenario> build,
                                    BiConsumer<TestServer, Scenario> body, boolean expectFlags) {
        if (skip(name)) {
            return;
        }
        SuiteVerdict.expect(name);
        Report.caseStarted(name);
        Coverage.currentCase(name);
        LogTap.install();
        long logMark = LogTap.mark();
        Scenario scenario = new Scenario(ctx, name);
        try {
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
                String verdict;
                if (expectFlags) {
                    verdict = "flags expected; " + scenario.flags().size() + " line(s) since the last mark";
                } else {
                    ctx.waitTicks(20);      // let a late verbose line land before looking
                    scenario.assertClean();
                    verdict = anticheatMuted() ? "no anticheat (-Pnogrim)" : "clean";
                }
                SuiteVerdict.finished(name);
                scenario.report(expectFlags ? "FLAGGED" : "PASS", verdict, "", logMark);
            }
        } catch (RuntimeException | Error t) {
            scenario.report("FAIL", "", t.getClass().getSimpleName() + ": " + t.getMessage(), logMark);
            throw t;
        } finally {
            scenario.disconnect();
            serverLabel = null;
        }
    }

    /** One row in the run's report, with this scenario's server console and client log attached. */
    private void report(String status, String anticheat, String detail, long logMark) {
        List<String> serverLines = server == null ? List.of() : server.tail(600).lines().toList();
        Report.caseFinished(name, status, anticheat, detail, serverLines, LogTap.since(logMark));
    }

    // ---- shared-server mode (hx.Session) -------------------------------------------------------------------

    /**
     * For {@code hx.Session}: connect this client to an already started server, arm the anticheat and sweep the
     * world - what {@link #run} does before its build step - and hand back the scenario so the session can read
     * flags, re-connect after a case that got kicked, and leave at the end. The label set with
     * {@link #labelServerAs} is used and kept until {@link #leave}.
     */
    public static Scenario attach(ClientGameTestContext ctx, String name, TestServer server) {
        Scenario scenario = new Scenario(ctx, name);
        scenario.server = server;
        scenario.connect();
        scenario.arm(server);
        server.command("testkit sweep");
        ctx.waitTicks(10);
        return scenario;
    }

    /** Whether the client is still in this server's world. */
    public boolean connected() {
        return ctx.computeOnClient(mc -> mc.player != null && mc.level != null);
    }

    /** Join again after a case was kicked or disconnected. Uses the same label as the first join. */
    public void reconnect() {
        disconnect();
        connect();
    }

    /** Leave the server and go back to the title screen; clears the server label. Safe to call twice. */
    public void leave() {
        try {
            disconnect();
        } finally {
            serverLabel = null;
        }
    }

    /** Whether flags since the last mark should fail: false under -Pnogrim, where nothing is judged. */
    public void assertNoFlags() {
        assertClean();
    }

    /**
     * Join the server, failing with the server's own words if it turns the client away.
     *
     * <p>A refused login otherwise surfaces as {@code Timed out waiting for predicate} a minute later, when the
     * server said exactly what was wrong the moment it happened — and put it on a screen nobody reads.
     */
    /** On the "Failed to connect" screen with no connection: the join died before the server said anything. */
    private static boolean connectFailed(net.minecraft.client.Minecraft mc) {
        return mc.getConnection() == null && dev.testkit.compat.McCompat.screen(mc)
                instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
    }

    private static String describeScreen(net.minecraft.client.Minecraft mc) {
        var screen = dev.testkit.compat.McCompat.screen(mc);
        return (screen == null ? "no screen" : screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString()
                + "\"") + ", connection " + (mc.getConnection() == null ? "none" : "open");
    }

    private void connect() {
        // A join can fail before any packet listener exists ("Failed to connect to the server", a DisconnectedScreen
        // with no connection), which Disconnects never hears. On 26.2 the second join of a run did exactly that
        // every time, the moment the restarted server said Done (2026-10-04), and then sat out the whole minute.
        // That one is retried; anything the server SAYS is still a refusal and fails at once.
        for (int attempt = 1; ; attempt++) {
            Disconnects.clear();
            ctx.runOnClient(mc -> {
                String label = serverLabel == null ? TestServer.address() : serverLabel;
                ServerData data = new ServerData("testkit", label, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(new TitleScreen(), mc,
                        ServerAddress.parseString(TestServer.address()), data, false, null);
            });
            try {
                ctx.waitFor(mc -> (mc.player != null && mc.level != null) || Disconnects.last() != null
                        || connectFailed(mc), 1200);
            } catch (AssertionError timedOut) {
                throw new AssertionError("[" + name + "] never joined " + TestServer.address() + " in 60 s: "
                        + ctx.computeOnClient(Scenario::describeScreen), timedOut);
            }
            if (Disconnects.last() != null || !ctx.computeOnClient(Scenario::connectFailed)) {
                break;
            }
            String why = ctx.computeOnClient(Scenario::describeScreen);
            if (attempt >= 5) {
                throw new AssertionError("[" + name + "] could not reach " + TestServer.address() + " in " + attempt
                        + " tries: " + why);
            }
            System.out.println("[" + name + "] join " + attempt + " failed (" + why + ") - retrying in 2 s");
            ctx.waitTicks(40);
        }
        String refused = Disconnects.last();
        if (refused != null) {
            throw new AssertionError("[" + name + "] the test server refused the connection: \"" + refused
                    + "\". If it mentions a profile, a session or verifying a username, check that "
                    + "run/testserver/server.properties still says online-mode=false.");
        }
        // A scenario run opens a window and plays twenty minutes of Minecraft at whatever the machine's
        // volume happens to be.
        ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(
                net.minecraft.sounds.SoundSource.MASTER).set(0.0));
        ctx.waitTicks(40);
        System.out.println("[" + name + "] connected as "
                + ctx.computeOnClient(mc -> mc.player.getGameProfile().name()));
    }

    private void disconnect() {
        // Nothing a body turns on outlives its scenario. The latency stage goes with the connection; the
        // trace keeps what it recorded, readable after run() returns, but stops adding to it.
        PacketTrace.stop();
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
            System.out.println("[" + name + "] anticheat removed by -Pnogrim — nothing this run says is a verdict");
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
            System.out.println("[" + name + "] " + (anticheatMuted()
                    ? "no anticheat on the server (-Pnogrim) — the body ran, nothing was judged"
                    : "anticheat clean — no verbose lines"));
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
     *
     * <p>Anything the body itself drew is a real result and must not be what "proves" the detector. An
     * earlier version accepted any verbose line since the body's mark as proof, then marked past it — so a
     * module that flagged during the body printed "positive control OK" followed by "anticheat clean". A
     * flag from the body now fails the scenario here, the way {@link #run}'s own check would have.
     */
    public void assertDetectorWorks() {
        if (anticheatMuted()) {
            return;
        }
        List<String> fromBody = flags();
        if (!fromBody.isEmpty()) {
            StringBuilder message = new StringBuilder("[" + name + "] anticheat flagged "
                    + fromBody.size() + " time(s) during the body, before the positive control:");
            for (String flag : fromBody) {
                message.append(System.lineSeparator()).append("    ").append(flag);
            }
            System.out.println(message);
            throw new AssertionError(message.toString());
        }
        // Twice, because the client JVM is shared across scenarios and a mod under test can be left in a
        // state that swallows the first cheat (holding packets, say). A second attempt from a fresh mark
        // either produces a flag or fails the run honestly; it can never turn a dead detector green.
        if (!detectorSeesCheat()) {
            server.mark();
            if (!detectorSeesCheat()) {
                throw new AssertionError("Positive control FAILED twice: teleported six blocks sideways and "
                        + "the anticheat said nothing. Every 'clean' result below this is meaningless.");
            }
        }
        System.out.println("[" + name + "] positive control OK — detector saw: " + flags().get(0));
        server.mark();
    }

    /** Cheat once and report whether the anticheat noticed. */
    private boolean detectorSeesCheat() {
        ctx.runOnClient(mc -> mc.player.setPos(mc.player.getX() + 6.0, mc.player.getY(), mc.player.getZ()));
        ctx.waitTicks(60);
        return !flags().isEmpty();
    }

    /**
     * Attack whatever the crosshair is on, each time the swing has fully recharged, for {@code ticks} ticks.
     *
     * <p>Holding the attack key does not do this. Vanilla swings at an entity once per key <i>press</i> —
     * holding repeats only for breaking blocks — so a held key is one hit and then {@code ticks} of standing
     * there: a fight that never happened, reported as a clean one.
     *
     * @return how many swings were made
     */
    public int attackFor(int ticks) {
        int swings = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (ctx.computeOnClient(mc -> mc.player.getAttackStrengthScale(0f) >= 1.0f)) {
                ctx.getInput().pressKey(options -> options.keyAttack);
                swings++;
            }
            ctx.waitTick();
        }
        return swings;
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
