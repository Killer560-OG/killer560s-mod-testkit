package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;
import java.util.Locale;

/**
 * 1126-zoom-glide-* (AGreenMan via killer560, 2026-11-26: "when you move your mouse it glides ... as long as you are holding
 * the zoom button your view slowly decelerates and glides to a stop"). Zoom's Smooth Glide runs the real mouse turn through
 * vanilla's cinematic smoother while the zoom key (here mouse button 4, held with the REAL input path) is down.
 * default: a fresh config has it OFF. on: the look keeps changing after the mouse stops, then settles, and releasing zoom
 * stops it at once. off: the same mouse move with zoom held stops with the mouse. All three fail on a jar without the switch.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ZoomGlide1126Tests implements FabricClientGameTest {

    private static final String CFG = "zoom.ZoomConfig";
    private static final String FEATURE = "zoom.ZoomFeature";
    /** Mouse button 4 stored as a bind: -100 - 3. Vanilla does nothing with it. */
    private static final int ZOOM_BIND = -103;
    private static final int SURFACE_Y = 151;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (java.util.function.Consumer<ClientGameTestContext> c : List.<java.util.function.Consumer<ClientGameTestContext>>of(
                ZoomGlide1126Tests::defaults, ZoomGlide1126Tests::glideOn, ZoomGlide1126Tests::glideOff)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void arena(TestServer server, int x, int z) {
        TestMap.on(server).platform(x, SURFACE_Y - 1, z, 8).catchFloor(120).survival().clearInventory()
                .spawn(x + 0.5, z + 0.5, 0f).build();
    }

    /** 1126-zoom-glide-default: a brand-new config has Smooth Glide OFF, and it survives save and load. */
    private static void defaults(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1126-zoom-glide-default", (server, scenario) -> arena(server, -2800, -2600), (server, scenario) -> {
            String got = ctx.computeOnClient(mc -> {
                try {
                    var ctor = Mod.cls(CFG).getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object fresh = ctor.newInstance();
                    String def = String.valueOf(Mod.call(fresh, "isSmoothGlide"));
                    Mod.set(CFG, "setSmoothGlide", true);
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.set(CFG, "setSmoothGlide", false);
                    Mod.staticCall(CFG, "load");
                    String loaded = String.valueOf(Mod.call(Mod.cfg(CFG), "isSmoothGlide"));
                    Mod.set(CFG, "setSmoothGlide", false);
                    Mod.call(Mod.cfg(CFG), "save");
                    return def + " " + loaded;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            scenario.log("default and after save/load of ON: " + got);
            check(got.equals("false true"), "Smooth Glide default or persistence wrong: " + got);
        });
    }

    /** 1126-zoom-glide-on: after the mouse stops the look keeps moving, settles, and releasing zoom stops it at once. */
    private static void glideOn(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1126-zoom-glide-on", (server, scenario) -> arena(server, -2900, -2600), (server, scenario) -> {
            try {
                float[] y = run(ctx, true);
                scenario.log(String.format(Locale.ROOT, "on: yaw start %.2f, +5t %.2f, +25t %.2f, settled %.2f/%.2f, release %.2f/%.2f",
                        y[0], y[1], y[2], y[3], y[4], y[5], y[6]));
                check(y[1] - y[0] > 0.5f, "the mouse move did not turn the view at all: " + (y[1] - y[0]));
                check(Math.abs(y[2] - y[1]) > 0.3f, "the view did not keep gliding after the mouse stopped: " + y[1] + " -> " + y[2]);
                check(Math.abs(y[4] - y[3]) < 0.02f, "the glide never came to a stop: " + y[3] + " -> " + y[4]);
                check(Math.abs(y[6] - y[5]) < 0.02f, "releasing zoom did not stop the glide at once: " + y[5] + " -> " + y[6]);
            } finally {
                reset(ctx);
            }
        });
    }

    /** 1126-zoom-glide-off: the same move with the switch OFF stops with the mouse. */
    private static void glideOff(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1126-zoom-glide-off", (server, scenario) -> arena(server, -3000, -2600), (server, scenario) -> {
            try {
                float[] y = run(ctx, false);
                scenario.log(String.format(Locale.ROOT, "off: yaw start %.2f, +5t %.2f, +25t %.2f", y[0], y[1], y[2]));
                check(y[1] - y[0] > 0.5f, "the mouse move did not turn the view: " + (y[1] - y[0]));
                check(Math.abs(y[2] - y[1]) < 0.02f, "with Smooth Glide OFF the view kept moving: " + y[1] + " -> " + y[2]);
            } finally {
                reset(ctx);
            }
        });
    }

    /**
     * Holds zoom (mouse button 4), moves the mouse once, and samples the player's yaw. Returns {start, +5 ticks, +25 ticks,
     * after settling, 20 ticks later, at release, 10 ticks after release}; the last four are only filled when {@code glide}.
     */
    private static float[] run(ClientGameTestContext ctx, boolean glide) {
        ctx.runOnClient(mc -> {
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setKeyCode", ZOOM_BIND);
            Mod.set(CFG, "setSmoothGlide", glide);
            mc.options.sensitivity().set(1.0); // a faster glide, so the case settles in seconds
        });
        // MouseHandler drops the first cursor move after the mouse is grabbed; spend it here.
        ctx.getInput().moveCursor(1, 0);
        ctx.waitTicks(3);
        ctx.getInput().holdMouse(dev.testkit.compat.McKeys.mouse(3));
        ctx.waitTicks(4);
        boolean active = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(FEATURE, "glideActive"));
        check(active == glide, "Zoom held with Smooth Glide " + (glide ? "ON" : "OFF") + " but glideActive was " + active);
        float[] y = new float[7];
        y[0] = yaw(ctx);
        ctx.getInput().moveCursor(20, 0);
        ctx.waitTicks(5);
        y[1] = yaw(ctx);
        ctx.waitTicks(20);
        y[2] = yaw(ctx);
        if (!glide) {
            ctx.getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(3));
            return y;
        }
        ctx.waitTicks(100);
        y[3] = yaw(ctx);
        ctx.waitTicks(20);
        y[4] = yaw(ctx);
        // Release in the middle of a new glide.
        ctx.getInput().moveCursor(-20, 0);
        ctx.waitTicks(6);
        ctx.getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(3));
        ctx.waitTicks(3);
        y[5] = yaw(ctx);
        ctx.waitTicks(10);
        y[6] = yaw(ctx);
        return y;
    }

    private static float yaw(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getYRot());
    }

    private static void reset(ClientGameTestContext ctx) {
        try {
            ctx.getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(3));
            ctx.runOnClient(mc -> {
                if (Mod.has(CFG)) {
                    Mod.set(CFG, "setSmoothGlide", false);
                    Mod.set(CFG, "setEnabled", false);
                }
                mc.options.sensitivity().set(0.5);
            });
        } catch (Throwable ignored) {
            // cleanup must not become the failure
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
