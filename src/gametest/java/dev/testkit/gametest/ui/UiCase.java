package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Coverage;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A client-only case: WP4 runs in singleplayer (or at the title screen) and never starts the test server, so it
 * cannot use {@code hx.Session}. This gives each case what a Session case gets - its own report row and log slice,
 * a screenshot on failure, an entry for {@code -Pfailed}, and {@link Mod#assertHealthy} - and lets the next case run
 * after a failure.
 *
 * <p>Selection is {@link Scenario#skip}: {@code -Psuite=ui} (filter {@code -ui-}) selects every case, and
 * {@code -Pscenario=ui-smoke} only the smoke group.
 */
public final class UiCase {

    /** A case body. */
    @FunctionalInterface
    public interface Body {
        void run(UiCase c) throws Exception;
    }

    private final ClientGameTestContext ctx;
    private final String name;
    private final List<String> problems = new ArrayList<>();

    private UiCase(ClientGameTestContext ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    /** Whether {@code name} is selected by the current filter (without starting it). */
    public static boolean selected(String name) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank()) {
            return true;
        }
        for (String part : filter.split(",")) {
            if (!part.isBlank() && name.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Run one case. Returns true if it passed (false if it failed or was not selected). */
    public static boolean run(ClientGameTestContext ctx, String name, Body body) {
        if (Scenario.skip(name)) {
            return false;
        }
        SuiteVerdict.expect(name);
        Report.caseStarted(name);
        Coverage.currentCase(name);
        LogTap.install();
        long logMark = LogTap.mark();
        UiCase c = new UiCase(ctx, name);
        long started = System.nanoTime();
        try {
            Mod.Mark health = Mod.mark();
            body.run(c);
            ctx.waitTicks(2);
            if (!c.problems.isEmpty()) {
                throw new AssertionError("[" + name + "] " + c.problems.size() + " problem(s):\n    "
                        + String.join("\n    ", c.problems));
            }
            Mod.assertHealthy(health);
            SuiteVerdict.finished(name);
            String detail = String.format(java.util.Locale.ROOT, "%.1f s", (System.nanoTime() - started) / 1e9);
            Report.caseFinished(name, "PASS", "n/a (singleplayer)", detail, List.of(), LogTap.since(logMark));
            System.out.println("[" + name + "] PASS");
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            try {
                Path shot = ctx.takeScreenshot(Report.fileName(name));
                Report.screenshot(name, shot);
            } catch (Throwable shotFailed) {
                System.out.println("[" + name + "] no screenshot: " + shotFailed);
            }
            SuiteVerdict.failCase(name, t);
            Report.caseFinished(name, "FAIL", "n/a (singleplayer)", t.getClass().getSimpleName() + ": "
                    + t.getMessage(), List.of(), LogTap.since(logMark));
            return false;
        } finally {
            // Never leave a mod screen up for the next case.
            try {
                ctx.runOnClient(mc -> mc.setScreen(null));
            } catch (Throwable ignored) {
                // the next case sets its own screen anyway
            }
            Coverage.currentCase("");
        }
    }

    public ClientGameTestContext ctx() {
        return ctx;
    }

    public String name() {
        return name;
    }

    /** A line in the case's log and report notes. */
    public void note(String text) {
        Report.note(name, text);
    }

    /**
     * Record a problem without stopping the case: a sweep over 170 tabs should report every broken tab, not the
     * first. The case fails at the end if any were recorded.
     */
    public void problem(String text) {
        problems.add(text);
        System.out.println("[" + name + "] PROBLEM " + text);
    }

    public int problemCount() {
        return problems.size();
    }

    /** Fail now. */
    public void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[" + name + "] " + message);
        }
    }

    public <T> T onClient(Function<Minecraft, T> fn) {
        return ctx.computeOnClient(fn::apply);
    }

    public void ticks(int n) {
        ctx.waitTicks(n);
    }

    /** One-line description of a throwable, with the deepest cause and the first mod frame. */
    public static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String where = "";
        for (StackTraceElement e : root.getStackTrace()) {
            if (e.getClassName().startsWith("com.killer560.")) {
                where = " at " + e.getClassName().replace("com.killer560.hub.", "") + "." + e.getMethodName()
                        + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
                break;
            }
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage() + where;
    }
}
