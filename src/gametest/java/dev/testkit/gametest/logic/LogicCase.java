package dev.testkit.gametest.logic;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Coverage;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One WP5 case at the title screen: no server, so no anticheat verdict. Mirrors {@code Session}'s per-case
 * bookkeeping (filter, SuiteVerdict expect/finished/failCase, Report row + log slice, Coverage, Mod health) without
 * a server.
 *
 * <p>Checks are SOFT: every {@link #check} runs and the case fails once at the end listing every failed check, so
 * one wrong expectation never hides the next. A case that ran zero checks fails - it tested nothing.
 */
final class LogicCase {

    /** The body of a case. */
    interface Body {
        void run(LogicCase c) throws Exception;
    }

    private final String name;
    private final boolean silent;
    private int checks;
    private final List<String> failures = new ArrayList<>();
    private int skipped;

    private LogicCase(String name, boolean silent) {
        this.name = name;
        this.silent = silent;
    }

    /** A collector that reports nothing: for previews whose problems must not fail the case running them. */
    static LogicCase collector(String name) {
        return new LogicCase(name, true);
    }

    List<String> failures() {
        return failures;
    }

    /**
     * Run one case on the client thread (mod classes and config singletons belong to it). Returns true when it ran
     * and passed, false when it failed, null when the filter left it out.
     */
    static Boolean run(ClientGameTestContext ctx, String name, Body body) {
        if (Scenario.skip(name)) {
            return null;
        }
        SuiteVerdict.expect(name);
        Report.caseStarted(name);
        Coverage.currentCase(name);
        long logMark = LogTap.mark();
        LogicCase c = new LogicCase(name, false);
        Throwable thrown;
        try {
            thrown = ctx.computeOnClient(mc -> {
                try {
                    Mod.Mark health = ModUnderTest.loaded("killer560smod") ? Mod.mark() : null;
                    body.run(c);
                    if (health != null) {
                        Mod.assertHealthy(health);
                    }
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
        } catch (Throwable t) {
            thrown = t;
        }
        try {
            if (thrown instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (thrown == null && c.checks == 0) {
                thrown = new AssertionError("[" + name + "] ran no checks - it tested nothing");
            }
            if (thrown == null && !c.failures.isEmpty()) {
                thrown = new AssertionError(c.failures.size() + " of " + c.checks + " check(s) failed: "
                        + String.join(" || ", c.failures));
            }
            String summary = c.checks + " check(s)" + (c.skipped > 0 ? ", " + c.skipped + " skipped" : "");
            if (thrown == null) {
                SuiteVerdict.finished(name);
                Report.caseFinished(name, "PASS", "n/a (no server)", summary, List.of(), LogTap.since(logMark));
                System.out.println("[" + name + "] PASS (" + summary + ")");
                return true;
            }
            SuiteVerdict.failCase(name, thrown);
            Report.caseFinished(name, "FAIL", "n/a (no server)", thrown.getClass().getSimpleName() + ": "
                    + thrown.getMessage(), List.of(), LogTap.since(logMark));
            return false;
        } finally {
            Coverage.currentCase("");
        }
    }

    String name() {
        return name;
    }

    /** A soft check: counted, and on failure recorded (with {@code detail}) without stopping the case. */
    boolean check(String label, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures.add(label + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            if (!silent) {
                Report.note(name, "FAIL " + label + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            }
        }
        return ok;
    }

    boolean check(String label, boolean ok) {
        return check(label, ok, "");
    }

    /** {@code expected.equals(actual)}, quoting both on failure. */
    boolean eq(String label, Object expected, Object actual) {
        return check(label, Objects.equals(expected, actual), "expected <" + expected + "> but was <" + actual + ">");
    }

    /** |expected - actual| <= tol. */
    boolean near(String label, double expected, double actual, double tol) {
        return check(label, Math.abs(expected - actual) <= tol,
                "expected " + expected + " +/- " + tol + " but was " + actual);
    }

    /** The supplier must not throw; returns its value (null when it threw - the failure is recorded). */
    <T> T noThrow(String label, Supplier<T> s) {
        try {
            T v = s.get();
            check(label, true);
            return v;
        } catch (Throwable t) {
            check(label, false, "threw " + rootCause(t));
            return null;
        }
    }

    /** A sub-check that could not run (named, with the reason). Never counts as a pass. */
    void skip(String label, String why) {
        skipped++;
        Report.note(name, "SKIP " + label + ": " + why);
    }

    void note(String text) {
        if (!silent) {
            Report.note(name, text);
        }
    }

    static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }
}
