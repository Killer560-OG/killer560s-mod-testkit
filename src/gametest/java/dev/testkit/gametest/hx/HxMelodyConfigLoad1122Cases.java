package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Team Melody's config used to be read on the render thread by its first caller (a HUD or tick call), a disk stall in a frame
 * (melodyhud-load-1122). Now the render thread gets defaults and no disk until the background read lands. Proves: with nothing
 * loaded the render thread's getInstance reads nothing (the saved HUD switch is not seen, the instance stays unloaded); the
 * background read then shows the saved values from a thread that is not the render thread; a profile apply (reloadAllConfigs)
 * still re-reads the file.
 */
final class HxMelodyConfigLoad1122Cases {

    private static final String CFG = "melody.MelodyHudConfig";

    private HxMelodyConfigLoad1122Cases() {
    }

    static void register(Session s) {
        s.test("1122-melody-config-load", HxMelodyConfigLoad1122Cases::load);
    }

    private static boolean hud(Session c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.staticCall(CFG, "getInstance"), "isHudEnabledRaw"));
    }

    private static void load(Session c) {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-teammelody.json"));
        String saved = null;
        try {
            saved = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"hudEnabled\":true,\"shareProgress\":true}", StandardCharsets.UTF_8);
            String renderThread = c.onClient(mc -> Thread.currentThread().getName());
            // A fresh game: nothing loaded yet.
            c.ctx().runOnClient(mc -> {
                Mod.setField(CFG, "instance", null);
                Mod.setField(CFG, "loadThreadForTest", null);
            });
            c.check(!hud(c), "the render thread read the saved HUD switch from disk on its first call");
            c.check(c.onClient(mc -> Mod.field(CFG, "instance")) == null, "the render thread's first call loaded the config");
            // The background read at client start.
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "loadInBackground"));
            c.waitUntil("the background read lands", mc -> Mod.field(CFG, "instance") != null, 100);
            String loader = c.onClient(mc -> (String) Mod.field(CFG, "loadThreadForTest"));
            c.check(loader != null && !loader.equals(renderThread), "the config was read on the render thread: " + loader);
            c.check(hud(c), "the saved HUD switch was not seen after the background read");
            // A profile apply re-reads the file.
            Files.writeString(file, "{\"hudEnabled\":false,\"shareProgress\":false}", StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs"));
            c.check(!hud(c), "a profile apply did not re-read the config");
            c.note("render thread read nothing; background thread '" + loader + "' loaded the saved values; profile apply re-read");
        } catch (java.io.IOException e) {
            c.check(false, "file access failed: " + e);
        } finally {
            try {
                if (saved == null) Files.deleteIfExists(file); else Files.writeString(file, saved, StandardCharsets.UTF_8);
            } catch (java.io.IOException ignored) {
                // test file cleanup
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
        }
    }
}
