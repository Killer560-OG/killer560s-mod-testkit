package dev.testkit.gametest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A real dedicated Minecraft server, with GrimAC on it, run as a <b>separate process</b> for the
 * duration of a scenario.
 *
 * <h2>Why a separate process</h2>
 * The obvious approach — the client gametest API's own {@code createServer()} — runs the server inside
 * the client's JVM, and that JVM is {@code EnvType.CLIENT}. In that environment the PacketEvents build
 * GrimAC bundles installs client-side hooks that are incomplete on 26.1.2:
 * {@code FabricPacketEventsAPIManagerFactory.getClientLazyPlayerManagerHolder()} returns null and the
 * client's own connection dies with <i>Invalid player data</i> the instant it joins. Its pre-launch
 * additionally calls {@code addAll} on the immutable list {@code FabricLoader.getEntrypoints} returns,
 * but only down the client branch. Neither happens in {@code EnvType.SERVER}. So the anticheat gets its
 * own process and the client stays clean — which is also how it runs in production, and therefore what
 * the test should be reproducing anyway.
 *
 * <p>The launch spec (java binary, classpath, main class, working directory) is written by Gradle's
 * {@code writeTestServerLaunch} task from loom's own {@code runTestServer} configuration, so this
 * replays exactly what {@code ./gradlew runTestServer} would do rather than guessing at a dev launch.
 */
public final class TestServer implements AutoCloseable {

    /** The server's whole console, mirrored here: a failed startup explains itself hundreds of lines
     * above wherever a tail happens to begin. */
    private static Path consoleLog = Path.of("testserver-console.log");

    /** Long enough for a cold dev-classpath server with an anticheat on it. */
    private static final int START_TIMEOUT_SECONDS = 180;
    private static final int STOP_TIMEOUT_SECONDS = 30;

    private final Process process;
    private final Writer console;
    private final List<String> log = new ArrayList<>();
    private int marked;

    private TestServer(Process process) {
        this.process = process;
        this.console = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /** Where the client should connect. Matches the port in the staged {@code server.properties}. */
    public static String address() {
        return "localhost:25565";
    }

    /**
     * Launch the server and block until it reports itself ready.
     *
     * @throws AssertionError if it never becomes ready — with the tail of its log, because a server
     *                        that failed to start is a mod-loading problem and the reason is in there
     */
    public static TestServer start() {
        String spec = System.getProperty("testkit.testserver.launch");
        if (spec == null) {
            throw new AssertionError("No test server launch spec. Run via Gradle so writeTestServerLaunch "
                    + "has produced one, or pass -Dtestkit.testserver.launch=<file>.");
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of(spec))) {
            props.load(in);
        } catch (IOException e) {
            throw new AssertionError("Could not read the test server launch spec at " + spec, e);
        }

        // Beside the launch spec, i.e. in the project's build directory -- the client's working
        // directory is its own run folder, which is not where anyone goes looking.
        consoleLog = Path.of(spec).resolveSibling("testserver-console.log");

        List<String> command = new ArrayList<>();
        command.add(props.getProperty("java"));
        command.addAll(split(props.getProperty("jvmArgs")));
        command.add("-cp");
        command.add(props.getProperty("classpath"));
        command.add(props.getProperty("main"));
        command.addAll(split(props.getProperty("args")));
        command.add("nogui");

        File dir = new File(props.getProperty("dir"));
        dir.mkdirs();
        wipeWorld(dir);
        enableVerboseToConsole(dir);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new AssertionError("Could not start the anticheat test server", e);
        }

        TestServer server = new TestServer(process);
        CountDownLatch ready = new CountDownLatch(1);
        Thread pump = new Thread(() -> server.pump(ready), "anticheat-server-log");
        pump.setDaemon(true);
        pump.start();

        // Polled rather than one long await, because a server that dies during startup never signals
        // readiness and the useful information is its own output. Counting down when the stream closes
        // would be worse than useless: it makes a dead server report itself ready, and the client then
        // fails with a bare "connection refused" that says nothing about the real cause.
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
            while (!ready.await(500, TimeUnit.MILLISECONDS)) {
                if (!process.isAlive()) {
                    throw new AssertionError("Anticheat test server exited with code "
                            + process.exitValue() + " before it was ready:\n" + server.tail(25));
                }
                if (System.nanoTime() > deadline) {
                    server.close();
                    throw new AssertionError("Anticheat test server did not start within "
                            + START_TIMEOUT_SECONDS + "s:\n" + server.tail(25));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
            throw new AssertionError("Interrupted waiting for the anticheat test server", e);
        }
        System.out.println("[anticheat] test server ready on " + address());
        return server;
    }

    /**
     * Delete the saved world so every scenario starts on untouched ground.
     *
     * <p>Scenarios each get their own server process but shared one world folder, so whatever the last
     * one built was still standing when the next one arrived. That is how a scaffold run scored
     * "bridged=812, placed=0": the 812 was the previous scenario's arena platform, the module had
     * nothing to bridge over because the ground was already solid, and the barrier walls penned it in.
     * Player data goes too, which is also how a fake player from an earlier run stops coming back.
     */
    private static void wipeWorld(File dir) {
        for (String name : new String[]{"world", "world_nether", "world_the_end"}) {
            File world = new File(dir, name);
            if (world.exists()) {
                deleteTree(world);
            }
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    /**
     * Point GrimAC's verbose output at the server console.
     *
     * <p>Without this the harness cannot see check output at all, and every scenario reports clean — the
     * positive control catches it, but only if someone runs it. The config does not exist until the
     * server has started once, so a fresh clone would otherwise need a throwaway run before any result
     * meant anything. Patching here rather than in the build means it self-heals: whatever state the
     * config is in when a scenario starts, verbose is on by the time the server launches.
     *
     * <p>Console rather than chat because the alternative is granting the test player the alerts
     * permission, and a player with permissions is a player GrimAC treats as EXEMPT.
     */
    private static void enableVerboseToConsole(File dir) {
        File config = new File(dir, "config/GrimAC/config.yml");
        try {
            if (!config.exists()) {
                // First run on this machine: seed the one setting we need. Grim fills in every other key
                // with its defaults on load and rewrites the file.
                config.getParentFile().mkdirs();
                Files.writeString(config.toPath(),
                        "# Seeded by the anticheat test kit; GrimAC fills in the rest on first load."
                                + System.lineSeparator()
                                + "verbose:" + System.lineSeparator()
                                + "    print-to-console: true" + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                return;
            }
            String text = Files.readString(config.toPath(), StandardCharsets.UTF_8);
            String patched = text.replaceAll(
                    "(?m)^(verbose:\\s*\\R(?:\\s*#.*\\R)*\\s*print-to-console:\\s*)false", "$1true");
            if (!patched.equals(text)) {
                Files.writeString(config.toPath(), patched, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new AssertionError("Could not enable anticheat verbose output at " + config, e);
        }
    }

    private void pump(CountDownLatch ready) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (log) {
                    log.add(line);
                }
                // Mirrored to a file as well: the useful part of a failed startup is usually hundreds
                // of lines above wherever the tail happens to start.
                try {
                    Files.writeString(consoleLog, line + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logging must never be the reason a test fails
                }
                // Vanilla prints this once the server is accepting connections.
                if (line.contains("Done (") || line.contains("For help, type")) {
                    ready.countDown();
                }
            }
        } catch (IOException ignored) {
            // the process died; start() notices via isAlive and reports its tail
        }
    }

    /** Run a command on the server console, as the console. */
    public void command(String command) {
        try {
            console.write(command);
            console.write("\n");
            console.flush();
        } catch (IOException e) {
            throw new AssertionError("Could not send '" + command + "' to the test server", e);
        }
    }

    /**
     * Draw a line under the console output so far. {@link #since()} reports only what came after, which
     * is how a scenario measures its own body rather than everything since the server booted.
     */
    public void mark() {
        synchronized (log) {
            marked = log.size();
        }
    }

    /** How many lines since the last {@link #mark()} contain {@code needle}. */
    public int countSince(String needle) {
        int count = 0;
        for (String line : since()) {
            if (line.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    /** Server output since the last {@link #mark()}. */
    public List<String> since() {
        synchronized (log) {
            return new ArrayList<>(log.subList(Math.min(marked, log.size()), log.size()));
        }
    }

    /** The last {@code lines} lines of server output — the first place to look when a scenario breaks. */
    public String tail(int lines) {
        synchronized (log) {
            int from = Math.max(0, log.size() - lines);
            return String.join("\n", log.subList(from, log.size()));
        }
    }

    /** Whether the server printed anything matching {@code needle} — for asserting on its own log. */
    public boolean logged(String needle) {
        synchronized (log) {
            for (String line : log) {
                if (line.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        try {
            command("stop");
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (AssertionError e) {
            process.destroyForcibly();
        }
    }

    private static List<String> split(String packed) {
        List<String> out = new ArrayList<>();
        if (packed == null || packed.isEmpty()) {
            return out;
        }
        for (String part : packed.split("\u0000")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }
}
