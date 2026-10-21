package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;

/**
 * 1018-freelook-* (killer560, 2026-10-18: "add a custom f5 so you press f5 and it puts you in backwards f5 if the setting
 * is on. You can have an option to hold it or to toggle it. In this f5 you can choose a button to hold and pan around
 * you."). Freelook is camera only: every case checks that the CAMERA changed (camera type, the rendered camera's yaw and
 * pitch) and that the PLAYER's yaw and pitch did not, and runs against GrimAC ({@link Scenario#run} asserts silence).
 *
 * <p>All four fail on a jar without the feature: {@code freelook.FreelookConfig} does not exist there.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FreelookTests implements FabricClientGameTest {

    private static final String CFG = "freelook.FreelookConfig";
    private static final String MODE = "freelook.FreelookConfig$Mode";
    private static final String FEATURE = "freelook.FreelookFeature";
    private static final int F5 = 294;
    private static final int SURFACE_Y = 151;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (java.util.function.Consumer<ClientGameTestContext> c : List.<java.util.function.Consumer<ClientGameTestContext>>of(
                FreelookTests::setting, FreelookTests::toggle, FreelookTests::hold, FreelookTests::pan)) {
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
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, z, 8)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, z + 0.5, 0f)
                .build();
    }

    /** 1018-freelook-setting: saved and loaded from disk (a restart), default OFF; and with it OFF F5 is vanilla. */
    private static void setting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1018-freelook-setting", (server, scenario) -> arena(server, -2600, -2600), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                Object fresh = Mod.cfg(CFG);
                boolean defaultOn = (Boolean) Mod.call(fresh, "isEnabled");
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setMode", Mod.enumValue(MODE, "HOLD"));
                Mod.set(CFG, "setPanKeyCode", 342); // Left Alt
                Mod.call(Mod.cfg(CFG), "save");
                // Change the live object, then load from disk as a restart would.
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setMode", Mod.enumValue(MODE, "TOGGLE"));
                Mod.set(CFG, "setPanKeyCode", -1);
                Mod.staticCall(CFG, "load");
                Object loaded = Mod.cfg(CFG);
                String r = Mod.call(loaded, "isEnabled") + " " + Mod.call(loaded, "getMode") + " "
                        + Mod.call(loaded, "getPanKeyCode");
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setMode", Mod.enumValue(MODE, "TOGGLE"));
                Mod.set(CFG, "setPanKeyCode", -1);
                Mod.call(Mod.cfg(CFG), "save");
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                return new String[]{String.valueOf(defaultOn), r};
            });
            // OFF: F5 is vanilla's cycle (first -> back).
            ctx.getInput().pressKey(F5);
            ctx.waitTicks(3);
            CameraType offView = ctx.computeOnClient(mc -> mc.options.getCameraType());
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
            scenario.log("setting: default enabled " + got[0] + "; after save, change and load: " + got[1]
                    + "; with Freelook off F5 gave " + offView);
            check(got[0].equals("false"), "Freelook was not OFF by default");
            check(got[1].equals("true HOLD 342"), "the saved settings did not come back from disk: " + got[1]);
            check(offView == CameraType.THIRD_PERSON_BACK, "with Freelook off F5 did not do vanilla's cycle: " + offView);
        });
    }

    /** 1018-freelook-toggle: F5 goes straight to the backwards view and back; the player's rotation is untouched. */
    private static void toggle(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1018-freelook-toggle", (server, scenario) -> arena(server, -2640, -2600), (server, scenario) -> {
            try {
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setMode", Mod.enumValue(MODE, "TOGGLE"));
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
                ctx.waitTicks(3);
                float[] before = rotations(ctx);
                // 2026-10-18 release (8156ef47): the rear view is the Freelook key's press edge; F5 is vanilla again.
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.TRUE));
                ctx.waitTicks(3);
                CameraType on = ctx.computeOnClient(mc -> mc.options.getCameraType());
                float[] during = rotations(ctx);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.FALSE));
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.TRUE));
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.FALSE));
                ctx.waitTicks(3);
                CameraType off = ctx.computeOnClient(mc -> mc.options.getCameraType());
                float[] after = rotations(ctx);
                scenario.log(String.format(Locale.ROOT, "toggle: F5 -> %s (camera yaw %.1f, player yaw %.1f->%.1f), F5 -> %s, "
                        + "player yaw/pitch after %.2f/%.2f", on, during[2], before[0], during[0], off, after[0], after[1]));
                check(on == CameraType.THIRD_PERSON_BACK, "the rear view did not go to the back view: " + on);
                check(Math.abs(wrap(during[2] - during[0])) < 1f, "the camera was not behind the player: camera "
                        + during[2] + ", player " + during[0]);
                check(off == CameraType.FIRST_PERSON, "F5 again did not return to first person: " + off);
                check(same(before, during) && same(before, after), "the player's yaw or pitch changed");
            } finally {
                reset(ctx);
            }
        });
    }

    /** 1018-freelook-hold: the backwards view lasts while F5 is held. */
    private static void hold(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1018-freelook-hold", (server, scenario) -> arena(server, -2680, -2600), (server, scenario) -> {
            try {
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setMode", Mod.enumValue(MODE, "HOLD"));
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
                ctx.waitTicks(3);
                float[] before = rotations(ctx);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.TRUE));
                ctx.waitTicks(10);
                CameraType held = ctx.computeOnClient(mc -> mc.options.getCameraType());
                float[] during = rotations(ctx);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.FALSE));
                ctx.waitTicks(3);
                CameraType released = ctx.computeOnClient(mc -> mc.options.getCameraType());
                float[] after = rotations(ctx);
                scenario.log("hold: held -> " + held + ", released -> " + released);
                check(held == CameraType.THIRD_PERSON_BACK, "holding the Freelook key did not show the back view: " + held);
                check(released == CameraType.FIRST_PERSON, "releasing F5 did not return to first person: " + released);
                check(same(before, during) && same(before, after), "the player's yaw or pitch changed");
            } finally {
                reset(ctx);
            }
        });
    }

    /**
     * 1018-freelook-pan: holding the pan key, the mouse orbits the CAMERA (yaw moved, pitch clamped at -90) and the player's
     * yaw and pitch do not move; release puts the camera back behind him. Then the same in the backwards view.
     */
    private static void pan(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1018-freelook-pan", (server, scenario) -> arena(server, -2720, -2600), (server, scenario) -> {
            try {
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setMode", Mod.enumValue(MODE, "HOLD"));
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
                // MouseHandler drops the first cursor move after the mouse is grabbed (ignoreFirstMove, set on joining
                // the world), so the first real move would read as "the mouse did not move the camera". Spend it here.
                ctx.getInput().moveCursor(1, 0);
                ctx.waitTicks(3);
                float[] before = rotations(ctx);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.TRUE));
                ctx.waitTicks(3);
                CameraType panView = ctx.computeOnClient(mc -> mc.options.getCameraType());
                boolean panning = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(FEATURE, "isPanning"));
                ctx.getInput().moveCursor(120, 0);
                ctx.waitTicks(5);
                float[] turned = rotations(ctx);
                ctx.getInput().moveCursor(0, -3000);
                ctx.waitTicks(5);
                float[] up = rotations(ctx);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.FALSE));
                ctx.waitTicks(3);
                CameraType afterView = ctx.computeOnClient(mc -> mc.options.getCameraType());
                float[] after = rotations(ctx);

                // In the backwards view: pan, then release, and the camera faces back at him again.
                ctx.getInput().pressKey(F5);
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.TRUE));
                ctx.waitTicks(3);
                ctx.getInput().moveCursor(120, 0);
                ctx.waitTicks(5);
                float[] rearTurned = rotations(ctx);
                CameraType rearView = ctx.computeOnClient(mc -> mc.options.getCameraType());
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "panHeldForTest", Boolean.FALSE));
                ctx.waitTicks(3);
                float[] rearAfter = rotations(ctx);

                scenario.log(String.format(Locale.ROOT, "pan: view %s panning %s; after mouse right camera yaw %.1f (player %.1f), "
                                + "after mouse up camera pitch %.1f (player %.1f); released -> %s camera yaw %.1f; backwards view "
                                + "%s camera yaw %.1f after pan, %.1f after release (player %.1f)",
                        panView, panning, turned[2], turned[0], up[3], up[1], afterView, after[2], rearView, rearTurned[2],
                        rearAfter[2], rearAfter[0]));
                check(panView == CameraType.THIRD_PERSON_BACK && panning, "the pan key did not start a third-person pan: "
                        + panView + " " + panning);
                check(Math.abs(wrap(turned[2] - turned[0])) > 5f, "the mouse did not move the camera: camera " + turned[2]
                        + ", player " + turned[0]);
                check(up[3] >= -90.001f && up[3] <= -89f, "the camera pitch was not held at -90 looking up: " + up[3]);
                check(afterView == CameraType.FIRST_PERSON, "releasing the pan key did not return to first person: " + afterView);
                check(Math.abs(wrap(after[2] - after[0])) < 0.5f, "the camera did not return behind the player");
                check(rearView == CameraType.THIRD_PERSON_BACK, "a pan in the back view left it: " + rearView);
                check(Math.abs(wrap(rearTurned[2] - rearTurned[0])) > 5f, "the mouse did not move the back-view camera");
                check(Math.abs(wrap(rearAfter[2] - rearAfter[0])) < 0.5f, "the back-view camera did not come back behind him");
                check(same(before, turned) && same(before, up) && same(before, after) && same(before, rearTurned)
                        && same(before, rearAfter), "the player's yaw or pitch changed while panning");
            } finally {
                reset(ctx);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** {player yaw, player pitch, rendered camera yaw, rendered camera pitch}. */
    private static float[] rotations(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object cam = camera(mc);
            try {
                float cy = (Float) cam.getClass().getMethod("yRot").invoke(cam);
                float cp = (Float) cam.getClass().getMethod("xRot").invoke(cam);
                return new float[]{mc.player.getYRot(), mc.player.getXRot(), cy, cp};
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no Camera.yRot()/xRot()", e);
            }
        });
    }

    /** {@code GameRenderer.getMainCamera()} on 26.1.2, {@code mainCamera()} on 26.2. */
    private static Object camera(Minecraft mc) {
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                return mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
            } catch (NoSuchMethodException ignored) {
                // try the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("GameRenderer has no camera accessor");
    }

    private static boolean same(float[] a, float[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static void reset(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                if (Mod.has(FEATURE)) {
                    Mod.setField(FEATURE, "panHeldForTest", null);
                    Mod.set(CFG, "setEnabled", false);
                }
            });
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
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
