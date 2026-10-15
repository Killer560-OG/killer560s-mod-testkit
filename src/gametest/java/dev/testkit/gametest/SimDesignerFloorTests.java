package dev.testkit.gametest;

import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 75-sim-designer-floors: Design a Map's floor button (mod sim-designer-1015).
 *
 * <p>killer560 (2026-10-15): "Changing this button did not change the size of the map like it should if the map is
 * already generated and I press this button then it needs to regenerate automatically. If i generate with it already
 * set then it will generate less rooms but it still needs to be in that square style. Right now as you can see they
 * are sporadically placed."
 *
 * <p>With a floor generated, the REAL floor button is clicked through all eight floors (the mouse, on the real
 * screen). After each click the grid must hold a NEW plan (regenerated, not the old one left standing) whose rooms
 * fill exactly that floor's real rectangle ({@link DesignerKit#FLOOR_SIZES}) - no room outside it, no slot of it
 * empty. Then Generate is clicked at that floor and the fresh plan is held to the same rule. Screenshots of Entrance
 * and Floor 1.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerFloorTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-floors";

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        DesignerKit.prepare(ctx);
        try {
            run(ctx);
        } finally {
            DesignerKit.closeScreen(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s):\n  " + String.join("\n  ", failures));
        }
        println("PASS");
    }

    private void run(ClientGameTestContext ctx) {
        DesignerKit.openEditor(ctx);
        check(DesignerKit.isEditor(ctx), "the map designer did not open");
        String start = DesignerKit.floorLabel(ctx);
        println("designer opened on " + start);
        check(start != null, "no floor button on the designer");
        if (start == null) {
            return;
        }
        check(DesignerKit.click(ctx, "Generate"), "no Generate button");
        Object plan = DesignerKit.generated(ctx);
        check(plan != null, "Generate put no plan on the grid");
        if (plan == null) {
            return;
        }
        String before = DesignerKit.code(ctx, plan);
        int clicks = 0;
        // Eight clicks: every floor once, ending back where it started.
        for (int i = 0; i < DesignerKit.FLOOR_LABELS.length; i++) {
            String from = DesignerKit.floorLabel(ctx);
            check(DesignerKit.click(ctx, from), "could not click the floor button '" + from + "'");
            clicks++;
            String now = DesignerKit.floorLabel(ctx);
            int idx = indexOf(now);
            check(idx >= 0, "after a click the floor button reads '" + now + "'");
            if (idx < 0) {
                return;
            }
            int[] size = DesignerKit.FLOOR_SIZES[idx];
            Object after = DesignerKit.generated(ctx);
            if (after == null) {
                check(false, now + ": the floor button took the generated floor off instead of regenerating it");
                continue;
            }
            String code = DesignerKit.code(ctx, after);
            String rect = DesignerKit.rectangleProblem(DesignerKit.cellRoom(ctx, after), size[0], size[1]);
            println(String.format("click %d -> %s (%dx%d): regenerated %s, %s", clicks, now, size[0], size[1],
                    !code.equals(before), rect == null ? "fills its rectangle exactly" : rect));
            check(!code.equals(before), now + ": the floor button did not regenerate - the grid still holds the "
                    + "previous floor's plan");
            check(rect == null, now + " after the floor button: " + rect);
            if (idx <= 1) {
                Path p = shot(ctx, "after-button-" + now.replace(' ', '-').toLowerCase());
                println("screenshot " + p);
            }
            // Generate at this floor, as he would after picking it.
            check(DesignerKit.click(ctx, "Generate"), "no Generate button");
            Object fresh = DesignerKit.generated(ctx);
            if (fresh == null) {
                check(false, now + ": Generate put no plan on the grid");
                continue;
            }
            String freshRect = DesignerKit.rectangleProblem(DesignerKit.cellRoom(ctx, fresh), size[0], size[1]);
            println("  Generate at " + now + ": " + (freshRect == null ? "fills its rectangle exactly" : freshRect)
                    + " - status '" + DesignerKit.field(ctx, "status") + "'");
            check(freshRect == null, now + " after Generate: " + freshRect);
            before = DesignerKit.code(ctx, fresh);
        }
        check(start.equals(DesignerKit.floorLabel(ctx)), "eight clicks did not come back to " + start);
    }

    private static int indexOf(String label) {
        for (int i = 0; i < DesignerKit.FLOOR_LABELS.length; i++) {
            if (DesignerKit.FLOOR_LABELS[i].equals(label)) {
                return i;
            }
        }
        return -1;
    }

    private static Path shot(ClientGameTestContext ctx, String what) {
        ctx.waitTicks(3);
        Path p = ctx.takeScreenshot(Report.fileName(NAME + "-" + what));
        Report.screenshot(NAME, p);
        return p;
    }

    private void check(boolean ok, String problem) {
        if (!ok) {
            failures.add(problem);
            println("PROBLEM: " + problem);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
