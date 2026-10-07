package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 397-menu-petwheel-instant (mod branch petwheel-instant). killer560, 2026-10-07: releasing the Pet Wheel bind over a
 * pet "that same in-game tick with 0 delay it should do /pets to open the menu"; the pet click after it may wait.
 * <p>
 * Real input only: the bind (keypad 5) is held and released through Fabric's {@code TestInput}, which hands the
 * release to vanilla's {@code KeyboardHandler}, and click mode clicks through {@code TestInput.pressMouse}. The
 * cursor is put on the slice of a SECOND pet (slot 12), so the click the fake Pets menu records also proves which
 * slice was resolved. A tap on every serverbound packet records {@link FrameClock}'s tick and frame counters when
 * the {@code pets} chat command goes out, against the same counters read just before the release; in the gametest
 * lockstep nothing renders or ticks between that read and the input task, so "0 ticks, 0 frames" means the command
 * left inside the release itself.
 * <p>
 * Each mode runs three situations: released one tick after the wheel opened (the gate's three-tick screen-transition
 * settle the wheel's own open starts), released after the wheel sat open ten ticks, and released in the same
 * inter-tick window an automated actor ({@code MASK_SWAP_CMD}, higher priority) just claimed through
 * {@code ActionGate.tryAct}. Every summon must still end with the server recording the click on slot 12.
 */
final class PetWheelInstantCases {

    private static final String CFG = "petwheel.PetWheelConfig";
    /** GLFW_KEY_KP_5: bound to nothing in vanilla or the mod's defaults. */
    private static final int KEY = 325;
    private static final String PET2 = "minecraft:player_head[custom_data={id:\"PET\",uuid:\"hx-pet-2\",petInfo:'{\"type\":\"ENDER_DRAGON\",\"tier\":\"EPIC\"}'},"
            + "custom_name=[{text:\"[Lvl 87] \",color:\"gray\",italic:false},{text:\"Ender Dragon\",color:\"dark_purple\",italic:false}]]";

    private enum Situation { SETTLE, STEADY, AUTOMATION }

    private PetWheelInstantCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "397-menu-petwheel-instant", PetWheelInstantCases::instant);
    }

    private static boolean wheelOpen(net.minecraft.client.Minecraft mc) {
        Screen s = McCompat.screen(mc);
        return s != null && s.getClass().getName().endsWith(".petwheel.PetWheelScreen");
    }

    static void instant(Session c) throws Exception {
        MenuKit.reset(c);
        JsonObject spec = MenuKit.menu("menus.pets");
        spec.getAsJsonObject("slots").addProperty("12", PET2);
        JsonObject close = new JsonObject();
        close.addProperty("close", true);
        spec.getAsJsonObject("on").add("12", close);

        AtomicReference<long[]> sent = new AtomicReference<>();
        List<String> added = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "KeyCode", KEY);
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, "Pets", 100);
            c.waitUntil("both fixture pets known", mc -> ((List<?>) Mod.call(Mod.cfg(CFG), "getKnownPets")).stream()
                    .filter(p -> String.valueOf(Mod.call(p, "uuid")).startsWith("hx-pet-")).count() >= 2, 100);
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> {
                Object conf = Mod.cfg(CFG);
                for (String uuid : List.of("hx-pet-1", "hx-pet-2")) {
                    if (!(Boolean) Mod.call(conf, "isOnWheel", uuid)) {
                        Mod.call(conf, "addToWheel", uuid);
                        added.add(uuid);
                    }
                }
            });
            int[] slice = c.onClient(mc -> {
                Object conf = Mod.cfg(CFG);
                List<?> wheel = (List<?>) Mod.call(conf, "getWheelPets");
                int per = ((Number) Mod.call(conf, "getSliceCount")).intValue();
                int idx = -1;
                for (int i = 0; i < wheel.size(); i++) {
                    if ("hx-pet-2".equals(Mod.call(wheel.get(i), "uuid"))) {
                        idx = i;
                    }
                }
                return new int[]{idx, Math.min(per, wheel.size())};
            });
            c.check(slice[0] >= 0 && slice[0] < slice[1], "hx-pet-2 is not on the wheel's first page: " + slice[0] + " of " + slice[1]);
            boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows"));
            c.check(gate, "SkyblockGate.allows() is false - the wheel keybind is never polled here");
            c.hx().call("menu.onCommand", "name", "pets", "menu", spec);
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd && "pets".equals(cmd.command())) {
                    sent.compareAndSet(null, new long[]{FrameClock.tickCount(), FrameClock.frameCount(), System.nanoTime()});
                }
            };

            for (String mode : List.of("HOLD_RELEASE", "PRESS_CLICK")) {
                cfg.set(CFG, "Mode", Mod.enumValue(CFG + "$InteractionMode", mode));
                for (Situation situation : Situation.values()) {
                    String label = mode + "/" + situation;
                    try {
                        String line = summonOnce(c, mode.equals("HOLD_RELEASE"), situation, slice, sent);
                        lines.add(label + ": " + line);
                        if (!line.startsWith("0 ticks") || !line.contains("input task: true")) {
                            failures.add(label + " " + line);
                        }
                    } catch (AssertionError e) {
                        lines.add(label + ": ERROR " + e.getMessage());
                        failures.add(label + " " + e.getMessage());
                        c.ctx().getInput().releaseKey(KEY);
                        c.ctx().waitTicks(2);
                        c.ctx().runOnClient(mc -> {
                            if (wheelOpen(mc)) {
                                McCompat.setScreen(mc, null);
                            }
                        });
                        c.waitUntil("PetSummoner idle after a failed summon", mc ->
                                !(Boolean) Mod.staticCall("petwheel.PetSummoner", "isBusy"), 200);
                        MenuKit.reset(c);
                    }
                }
            }
        } finally {
            PacketTrace.tap = null;
            c.ctx().getInput().releaseKey(KEY);
            c.ctx().runOnClient(mc -> {
                Object conf = Mod.cfg(CFG);
                for (String uuid : added) {
                    Mod.call(conf, "removeFromWheel", uuid);
                }
            });
            c.hx().call("menu.onCommand", "name", "pets", "menu", null);
            MenuKit.reset(c);
        }
        for (String l : lines) {
            c.note(l);
        }
        c.check(failures.isEmpty(), "/pets was not sent in the release/click frame: " + failures);
    }

    /** One summon: open the wheel with the real bind, aim at hx-pet-2's slice, release (or click), measure. */
    private static String summonOnce(Session c, boolean hold, Situation situation, int[] slice,
                                     AtomicReference<long[]> sent) {
        sent.set(null);
        long clicksBefore = c.events("container.click").stream().filter(e -> e.get("slot").getAsInt() == 12).count();
        c.ctx().getInput().holdKey(KEY);
        c.waitUntil("the Pet Wheel to open on the bind", PetWheelInstantCases::wheelOpen, 20);
        if (situation != Situation.SETTLE) {
            c.ctx().waitTicks(10);
        }
        double[] px = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            double angle = -Math.PI / 2.0 + slice[0] * (Math.PI * 2.0 / slice[1]);
            double gx = s.width / 2.0 + Math.cos(angle) * 60.0;
            double gy = s.height / 2.0 - 6.0 + Math.sin(angle) * 60.0;
            return new double[]{gx * mc.getWindow().getScreenWidth() / s.width, gy * mc.getWindow().getScreenHeight() / s.height};
        });
        c.ctx().getInput().setCursorPos(px[0], px[1]);
        // One tick so a frame draws the wheel under the new cursor, as it would for a player who aimed: the old tick
        // poll resolves the slice from the last DRAWN position, and without this it read the stale centre and picked
        // nothing. The gate's settle from the wheel opening (three ticks from the tick after it opened) still covers it.
        c.ctx().waitTicks(1);
        String claimed = "";
        if (situation == Situation.AUTOMATION) {
            boolean ok = c.onClient(mc -> (Boolean) Mod.staticCall("util.ActionGate", "tryAct",
                    Mod.enumValue("util.ActionGate$Actor", "MASK_SWAP_CMD")));
            c.check(ok, "the automation stand-in (MASK_SWAP_CMD) could not claim the tick");
            claimed = ", tick claimed by MASK_SWAP_CMD";
        }
        long[] at = c.onClient(mc -> new long[]{FrameClock.tickCount(), FrameClock.frameCount(), System.nanoTime()});
        if (hold) {
            c.ctx().getInput().releaseKey(KEY);
        } else {
            c.ctx().getInput().pressMouse(0);
        }
        boolean inFrame = sent.get() != null;
        c.waitUntil("the pets command", mc -> sent.get() != null, 60);
        if (!hold) {
            c.ctx().getInput().releaseKey(KEY);
        }
        long[] s = sent.get();
        c.waitUntil("the fake Pets menu to record the click on hx-pet-2 (slot 12)", mc -> c.events("container.click").stream()
                .filter(e -> e.get("slot").getAsInt() == 12).count() > clicksBefore, 200);
        c.waitUntil("PetSummoner to finish", mc -> !(Boolean) Mod.staticCall("petwheel.PetSummoner", "isBusy"), 100);
        MenuKit.reset(c);
        return String.format(Locale.ROOT, "%d ticks, %d frames, %.1f ms from %s to /pets (sent inside the input task: %s)%s; pet clicked in slot 12",
                s[0] - at[0], s[1] - at[1], (s[2] - at[2]) / 1e6, hold ? "release" : "click", inFrame, claimed);
    }
}
