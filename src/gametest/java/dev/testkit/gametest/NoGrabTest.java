package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.OsInputGuard;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.Map;

/**
 * 1102-nograb (killer560, 2026-11-02: "my mouse is bugging out. i think it keeps getting put in the game instances on
 * the left monitor"): a test client never takes OS focus and never holds his cursor - {@link OsInputGuard}.
 *
 * <p>Joins the server (the game grabs the mouse logically on join), opens and closes the inventory (release, grab),
 * and with the mod calls the mod's own {@code McWindow.focus} and {@code setCursorHidden(.., grabbed)} - the native
 * focus and cursor-capture paths. Then requires, from the OS: the window never foreground, never OS-focused, the
 * cursor never captured and never clipped inside the window; and that the run did what it claims: enough samples, the
 * game's mouse logically grabbed at some point, WS_EX_NOACTIVATE on the window, and with the mod each cancelled call
 * counted (Confine Mouse's tick every tick).
 */
public class NoGrabTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1102-nograb",
                (server, s) -> TestMap.on(server).platform(8).catchFloor(140).survival().spawn(0.5, 0.5, 0f).build(),
                (server, s) -> body(ctx, s));
    }

    private static void body(ClientGameTestContext ctx, Scenario s) {
        if (!OsInputGuard.active()) {
            throw new AssertionError("testkit.osGrab=allow: the guard is off, nothing to prove");
        }
        boolean mod = FabricLoader.getInstance().isModLoaded("killer560smod");
        ctx.waitTicks(40);
        for (int i = 0; i < 3; i++) {
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            ctx.waitTicks(10);
        }
        if (mod) {
            ctx.runOnClient(mc -> {
                Mod.staticCall("compat.McWindow", "focus", mc.getWindow());
                Mod.staticCall("compat.McWindow", "setCursorHidden", mc.getWindow(), true, false);
                Mod.staticCall("compat.McWindow", "setCursorHidden", mc.getWindow(), false, true);
            });
        }
        ctx.waitTicks(40);
        String summary = ctx.computeOnClient(mc -> OsInputGuard.summary());
        s.log("[nograb] " + summary);
        Map<String, Integer> blocked = ctx.computeOnClient(mc -> OsInputGuard.blockedCalls());
        int samples = OsInputGuard.samples();
        StringBuilder bad = new StringBuilder();
        if (samples < 100) bad.append(" only ").append(samples).append(" samples;");
        if (OsInputGuard.logicalGrabTicks() == 0) bad.append(" the game's mouse was never logically grabbed;");
        if (!OsInputGuard.noActivateApplied()) bad.append(" WS_EX_NOACTIVATE not on the window;");
        if (OsInputGuard.foregroundTicks() > 0) bad.append(" foreground ").append(OsInputGuard.foregroundTicks()).append(" ticks;");
        if (OsInputGuard.osFocusedTicks() > 0) bad.append(" OS-focused ").append(OsInputGuard.osFocusedTicks()).append(" ticks;");
        if (OsInputGuard.capturedTicks() > 0) bad.append(" cursor captured ").append(OsInputGuard.capturedTicks()).append(" ticks;");
        if (OsInputGuard.clipInsideTicks() > 0) bad.append(" cursor clipped inside the window ").append(OsInputGuard.clipInsideTicks()).append(" ticks;");
        if (mod) {
            if (blocked.getOrDefault("McWindow.focus", 0) < 1) bad.append(" McWindow.focus not cancelled;");
            if (blocked.getOrDefault("McWindow.setCursorHidden", 0) < 2) bad.append(" McWindow.setCursorHidden not cancelled;");
            if (blocked.getOrDefault("ConfineMouseFeature.tick", 0) < 100) bad.append(" Confine Mouse tick not cancelled every tick;");
        }
        if (bad.length() > 0) {
            throw new AssertionError("[nograb]" + bad + " (" + summary + ")");
        }
        s.log("[nograb] clean: never foreground, never OS-focused, cursor never captured or clipped");
    }
}
