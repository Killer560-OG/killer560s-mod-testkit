package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.TestServer;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Coverage;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared-server mode: ONE server start (~70 s) for many cases, instead of one per scenario.
 *
 * <pre>{@code
 * public class HxSuite implements FabricClientGameTest {
 *     public void runTest(ClientGameTestContext ctx) {
 *         Session.run(ctx, "100-hx-session",
 *             (server, s) -> TestMap.on(server).platform(0, 150, 0, 8).catchFloor(140).survival()
 *                                   .spawn(0.5, 0.5, 0f).build(),
 *             s -> {
 *                 s.test("101-hx-floor-from-sidebar", c -> {
 *                     c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
 *                     c.waitUntil("DungeonState.getFloor()==F7",
 *                             mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
 *                 });
 *                 s.test("102-hx-...", c -> { ... });
 *             });
 *     }
 * }
 * }</pre>
 *
 * <h2>What a session does</h2>
 * Starts the server, labels the connection {@code mc.hypixel.net} (so the mod's server-address gates open; change
 * with {@link #run(ClientGameTestContext, String, String, Setup, Body)}), connects, arms the anticheat, sweeps,
 * connects the {@link Hx} bridge, applies {@link Quiet} (unless {@code -PnoQuiet}), runs {@code setup} (the map),
 * runs the body's cases, then - unless {@link #skipDetectorProof()} was called or {@code -Pnogrim} - runs the
 * anticheat positive control once, so every "clean" verdict in the session is backed by a detector that was shown to
 * work. Teardown (bridge, disconnect, server stop) is in a {@code finally}.
 *
 * <h2>What a case does</h2>
 * Each {@link #test} is its own verdict: a row in {@code build/testkit-report/summary.md}, a {@code cases/<name>.log}
 * with that case's server console and client log slice, a screenshot on failure, an entry in {@code -Pfailed}. A
 * failing case does NOT end the session; the next case runs (the client is re-connected first if the case got it
 * kicked). After the body the case is checked for:
 * <ul>
 *   <li>anticheat flags since the case started - a failure unless the case is {@link #testExpectingFlags};</li>
 *   <li>{@link Mod#assertHealthy}: no FeatureGuard disable, no ChatObserver failure, no killer560smod ERROR line.</li>
 * </ul>
 * A case must still prove its own premise (AGENTS.md section 2): assert the mod ACTED, not just that nothing broke.
 *
 * <h2>Selection</h2>
 * {@code -Pscenario=part,...}: the session runs if a part is contained in its name, contains its name, or equals its
 * tag ({@code -hx-} for {@code 100-hx-session}). A part that selects the session as a whole (contained in its name, or
 * the tag) runs every case; otherwise only cases whose names contain a part run.
 *
 * <p>Frozen after WP1.
 */
public final class Session {

    /** The map and anything else to do once, after connecting and before the first case. */
    @FunctionalInterface
    public interface Setup {
        void build(TestServer server, Session session) throws Exception;
    }

    /** The cases: calls to {@link Session#test}. */
    @FunctionalInterface
    public interface Body {
        void run(Session session) throws Exception;
    }

    /** One case. */
    @FunctionalInterface
    public interface Case {
        void run(Session session) throws Exception;
    }

    public static final String DEFAULT_LABEL = "mc.hypixel.net";

    private final ClientGameTestContext ctx;
    private final String name;
    private final String tag;
    private final boolean wholeSession;
    private TestServer server;
    private Scenario scenario;
    private Hx hx;
    private boolean proveDetector = true;
    private String currentCase;
    private long caseEventHead;
    private int passed;
    private int failed;
    private final List<String> failedCases = new ArrayList<>();

    private Session(ClientGameTestContext ctx, String name, boolean wholeSession) {
        this.ctx = ctx;
        this.name = name;
        this.tag = tagOf(name);
        this.wholeSession = wholeSession;
    }

    public static void run(ClientGameTestContext ctx, String name, Setup setup, Body body) {
        run(ctx, name, DEFAULT_LABEL, setup, body);
    }

    /** @param label the address the client believes it joined ({@code null} for plain localhost) */
    public static void run(ClientGameTestContext ctx, String name, String label, Setup setup, Body body) {
        Boolean whole = selection(name);
        if (whole == null) {
            return;
        }
        SuiteVerdict.expect(name);
        Report.caseStarted(name);
        Coverage.currentCase(name);
        LogTap.install();
        long logMark = LogTap.mark();
        Session session = new Session(ctx, name, whole);
        try {
            try (TestServer server = TestServer.start()) {
                session.server = server;
                if (label != null) {
                    Scenario.labelServerAs(label);
                }
                session.scenario = Scenario.attach(ctx, name, server);
                session.hx = Hx.connect();
                if (Quiet.enabled() && ModUnderTest.loaded("killer560smod")) {
                    List<String> quiet = ctx.computeOnClient(mc -> Quiet.apply());
                    System.out.println("[" + name + "] QUIET profile: " + quiet.size() + " switch(es) off");
                }
                setup.build(server, session);
                ctx.waitTicks(40);
                body.run(session);
                server.mark();
                if (session.proveDetector) {
                    session.scenario.assertDetectorWorks();
                }
                SuiteVerdict.finished(name);
                String detail = session.passed + " case(s) passed, " + session.failed + " failed"
                        + (session.failedCases.isEmpty() ? "" : ": " + session.failedCases);
                Report.caseFinished(name, "PASS", session.proveDetector && !Scenario.anticheatMuted()
                                ? "positive control OK" : "detector not proven", detail,
                        server.tail(200).lines().toList(), LogTap.since(logMark));
            }
        } catch (Exception e) {
            reportSessionFailure(session, e, logMark);
            throw new AssertionError("[" + name + "] session failed outside any case: " + e, e);
        } catch (Error e) {
            reportSessionFailure(session, e, logMark);
            throw e;
        } finally {
            if (session.hx != null) {
                session.hx.close();
            }
            if (session.scenario != null) {
                session.scenario.leave();
            }
            Coverage.currentCase("");
        }
    }

    private static void reportSessionFailure(Session session, Throwable t, long logMark) {
        List<String> serverLines = session.server == null ? List.of() : session.server.tail(300).lines().toList();
        Report.caseFinished(session.name, "FAIL", "", t.getClass().getSimpleName() + ": " + t.getMessage(),
                serverLines, LogTap.since(logMark));
    }

    /** Run a case that must leave the anticheat silent. */
    public void test(String caseName, Case body) {
        runCase(caseName, body, false);
    }

    /** Run a case expected to flag: flags are counted into the report instead of failing it. */
    public void testExpectingFlags(String caseName, Case body) {
        runCase(caseName, body, true);
    }

    /** Do not run the anticheat positive control at the end (only for a session that never quotes a clean verdict). */
    public void skipDetectorProof() {
        proveDetector = false;
    }

    private void runCase(String caseName, Case body, boolean expectFlags) {
        if (!caseSelected(caseName)) {
            return;
        }
        currentCase = caseName;
        SuiteVerdict.expect(caseName);
        Report.caseStarted(caseName);
        Coverage.currentCase(caseName);
        long logMark = LogTap.mark();
        try {
            if (!scenario.connected()) {
                System.out.println("[" + caseName + "] client was not connected; joining again");
                scenario.reconnect();
            }
            boolean modLoaded = ModUnderTest.loaded("killer560smod");
            Mod.Mark health = modLoaded ? Mod.mark() : null;
            server.mark();
            caseEventHead = hx.head();
            body.run(this);
            ctx.waitTicks(10);
            String verdict;
            List<String> flags = scenario.flags();
            if (Scenario.anticheatMuted()) {
                verdict = "no anticheat (-Pnogrim)";
            } else if (expectFlags) {
                verdict = "flags expected: " + flags.size() + " line(s)";
            } else if (!flags.isEmpty()) {
                throw new AssertionError("[" + caseName + "] anticheat flagged " + flags.size() + " time(s):\n    "
                        + String.join("\n    ", flags));
            } else {
                verdict = "clean";
            }
            if (health != null) {
                Mod.assertHealthy(health);
            }
            SuiteVerdict.finished(caseName);
            passed++;
            Report.caseFinished(caseName, expectFlags ? "FLAGGED" : "PASS", verdict, "", server.since(),
                    LogTap.since(logMark));
            System.out.println("[" + caseName + "] PASS (" + verdict + ")");
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            failed++;
            failedCases.add(caseName);
            try {
                Path shot = ctx.takeScreenshot(Report.fileName(caseName));
                Report.screenshot(caseName, shot);
            } catch (Throwable shotFailed) {
                System.out.println("[" + caseName + "] no screenshot: " + shotFailed);
            }
            SuiteVerdict.failCase(caseName, t);
            Report.caseFinished(caseName, "FAIL", "", t.getClass().getSimpleName() + ": " + t.getMessage(),
                    server.since(), LogTap.since(logMark));
        } finally {
            currentCase = null;
            Coverage.currentCase(name);
        }
    }

    // ---- selection -------------------------------------------------------------------------------------------

    /** null = skip the session; TRUE = every case; FALSE = only cases a filter part names. */
    private static Boolean selection(String name) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank()) {
            SuiteVerdict.started(name);
            return Boolean.TRUE;
        }
        String tag = tagOf(name);
        boolean any = false;
        boolean whole = false;
        for (String raw : filter.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (name.contains(part) || (tag != null && part.equals(tag))) {
                whole = true;
                any = true;
            } else if (part.contains(name)) {
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        SuiteVerdict.started(name);
        return whole;
    }

    private boolean caseSelected(String caseName) {
        if (wholeSession) {
            return true;
        }
        for (String raw : System.getProperty("testkit.scenario", "").split(",")) {
            String part = raw.trim();
            if (!part.isEmpty() && caseName.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** "100-hx-session" -> "-hx-"; null when the name has no second segment. */
    static String tagOf(String name) {
        String[] parts = name.split("-");
        return parts.length >= 3 ? "-" + parts[1] + "-" : null;
    }

    // ---- what a case uses -------------------------------------------------------------------------------------

    public ClientGameTestContext ctx() {
        return ctx;
    }

    public Hx hx() {
        return hx;
    }

    public TestServer server() {
        return server;
    }

    /** The underlying scenario: flags(), assertDetectorWorks(), playerPosition(), attackFor()... */
    public Scenario scenario() {
        return scenario;
    }

    public String name() {
        return currentCase != null ? currentCase : name;
    }

    /** A line in the case's log and report notes. */
    public void note(String text) {
        Report.note(name(), text);
    }

    /** Hx events of the given types since the current case started. */
    public List<JsonObject> events(String... types) {
        return hx.events(caseEventHead, types);
    }

    /** Commands the client sent since the current case started (without the slash). */
    public List<String> commands() {
        return hx.commands(caseEventHead);
    }

    /** Compute on the client thread. */
    public <T> T onClient(Function<Minecraft, T> fn) {
        return ctx.computeOnClient(fn::apply);
    }

    /** Wait until {@code condition} holds, at most {@code ticks}; fails naming {@code what}. */
    public void waitUntil(String what, Predicate<Minecraft> condition, int ticks) {
        try {
            ctx.waitFor(condition, ticks);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError("[" + name() + "] timed out after " + ticks + " ticks waiting for: " + what, e);
        }
    }

    /** Assert with a message. */
    public void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[" + name() + "] " + message);
        }
    }
}
