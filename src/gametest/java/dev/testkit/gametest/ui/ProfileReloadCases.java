package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Profile reloads (profreload-1102).
 * <ul>
 *   <li>{@link #nameAudit} (part of 320): every class in the jar that names a setting file a profile carries must be
 *       named by {@code ProfileManager} too, i.e. be in its reload or refresh lists. 320's value probe only reaches
 *       singleton {@code *Config} classes with a boolean {@code setX}/{@code isX} pair, so Streamer Mode (static
 *       methods), Legit GFS, Cape, the sim loadout and the sim room filters were never probed and never reloaded.</li>
 *   <li>{@link #streamer} (1102-ui-profile-streamer): applying a profile with Streamer Mode flipped changes the
 *       in-memory state at once, with no restart, and a profile without the file turns it back off.</li>
 * </ul>
 */
final class ProfileReloadCases {

    private static final Pattern SETTING_NAME = Pattern.compile("killer560smod-[A-Za-z0-9_.-]{1,80}\\.json");

    /**
     * Classes that may name a profile setting file without being in ProfileManager's lists, each with its reason. A
     * new row needs a reason that holds: "read fresh on every use" or "names it only to move/list it".
     */
    private static final Map<String, String> NAME_ALLOWED = Map.of(
            "profiles.ProfileManager", "the profile system itself",
            "profiles.ProfileData", "the profile system itself",
            "util.ModPaths", "its folder table and retired-name list",
            "configversion.ConfigMigrations", "rewrites files before their configs load",
            "autoroutes.AutoRoutesCommands", "javac inlined RouteStore.FILE_NAME (RouteStore::reload is listed)",
            "roomsim.SimMenuPrefs", "read from disk every time the sim menu opens, never held");

    private ProfileReloadCases() {
    }

    // ---- 320: names ----------------------------------------------------------------------------------------------

    /** Records a problem for each class that names a profile setting file and is not reloaded by ProfileManager. */
    static void nameAudit(UiCase c) {
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                Class<?> pm = Mod.cls("profiles.ProfileManager");
                Method isSetting = pm.getDeclaredMethod("isProfileSettingFile", String.class);
                isSetting.setAccessible(true);
                ModContainer mod = FabricLoader.getInstance().getModContainer(JarIndex.MOD_ID)
                        .orElseThrow(() -> new AssertionError("killer560smod is not loaded"));
                String pmBytes = null;
                // owner (rel name) -> setting files its classes name
                Map<String, TreeSet<String>> owners = new TreeMap<>();
                int classes = 0;
                for (Path root : mod.getRootPaths()) {
                    Path base = root.resolve("com/killer560/hub");
                    if (!Files.isDirectory(base)) {
                        continue;
                    }
                    List<Path> files;
                    try (Stream<Path> walk = Files.walk(base)) {
                        files = walk.filter(p -> p.toString().endsWith(".class")).toList();
                    }
                    for (Path p : files) {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String internal = rel.substring(0, rel.length() - ".class".length());
                        String top = internal.contains("$") ? internal.substring(0, internal.indexOf('$')) : internal;
                        String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                        classes++;
                        if (internal.equals("com/killer560/hub/profiles/ProfileManager")) {
                            pmBytes = text;
                        }
                        Matcher m = SETTING_NAME.matcher(text);
                        while (m.find()) {
                            String name = m.group();
                            if ((Boolean) isSetting.invoke(null, name)) {
                                owners.computeIfAbsent(JarIndex.rel(top.replace('/', '.')), k -> new TreeSet<>()).add(name);
                            }
                        }
                    }
                }
                if (pmBytes == null) {
                    throw new AssertionError("ProfileManager.class not found in the jar");
                }
                List<String> missing = new ArrayList<>();
                List<String> allowed = new ArrayList<>();
                int listed = 0;
                for (Map.Entry<String, TreeSet<String>> e : owners.entrySet()) {
                    String internal = (JarIndex.ROOT + e.getKey()).replace('.', '/');
                    if (pmBytes.contains("L" + internal + ";") || containsClassRef(pmBytes, internal)) {
                        listed++;
                    } else if (NAME_ALLOWED.containsKey(e.getKey())) {
                        allowed.add(e.getKey() + " (" + NAME_ALLOWED.get(e.getKey()) + ")");
                    } else {
                        missing.add(e.getKey() + " " + e.getValue());
                    }
                }
                out.put("classes", classes);
                out.put("owners", owners.size());
                out.put("listed", listed);
                out.put("allowed", allowed);
                out.put("missing", missing);
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            }
            return out;
        });
        c.check(!r.containsKey("error"), "name audit threw: " + r.get("error"));
        c.note("name audit: " + r.get("classes") + " class files read; " + r.get("owners")
                + " classes name a profile setting file; " + r.get("listed") + " are named by ProfileManager; allowed "
                + r.get("allowed"));
        c.check((Integer) r.get("owners") > 100, "only " + r.get("owners")
                + " classes name a profile setting file - the audit did not read the jar");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) r.get("missing");
        if (!missing.isEmpty()) {
            c.problem("these classes read a setting file that profiles carry, but ProfileManager never names them, so "
                    + "applying a profile leaves their old values in memory (add their load/reload to "
                    + "ProfileManager.reloadConfigsOnly, or the file to EXCLUDED_FILES if it is data): " + missing);
        }
    }

    /** A CONSTANT_Utf8 holding exactly the internal name (u2 length before it, the next tag after it). */
    private static boolean containsClassRef(String bytes, String internal) {
        int from = 0;
        while (true) {
            int i = bytes.indexOf(internal, from);
            if (i < 0) {
                return false;
            }
            int len = internal.length();
            if (i >= 2 && bytes.charAt(i - 2) == (char) (len >> 8) && bytes.charAt(i - 1) == (char) (len & 0xff)) {
                return true;
            }
            from = i + 1;
        }
    }

    // ---- 1102: Streamer Mode -------------------------------------------------------------------------------------

    static void streamer(UiCase c) {
        String withOn = "profreload-streamer-on";
        String without = "profreload-streamer-none";
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean startActive = (Boolean) Mod.staticCall("streamer.StreamerMode", "active");
            Object startOption = Mod.staticCall("streamer.StreamerMode", "option");
            out.put("startActive", startActive);
            Path file = null;
            String fileBefore = null;
            if (!Mod.isCheat()) {
                // toggle() is refused on the legit build: nothing to test there
                out.put("legit", true);
                return out;
            }
            try {
                if (startActive) {
                    Mod.staticCall("streamer.StreamerMode", "toggle"); // start from off
                }
                file = (Path) R.getStatic(Mod.cls("streamer.StreamerMode"), "CONFIG_PATH");
                fileBefore = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;

                // Profile "none": saved with no streamer file at all.
                Files.deleteIfExists(file);
                out.put("saveNone", resultOk(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", without)));

                // Profile "on": Streamer Mode on with Disable cheats.
                Object disable = Mod.enumValue("streamer.StreamerMode$Option", "DISABLE_CHEATS");
                Object hide = Mod.enumValue("streamer.StreamerMode$Option", "HIDE_CHEAT_UI");
                Mod.staticCall("streamer.StreamerMode", "setOption", disable);
                boolean on = (Boolean) Mod.staticCall("streamer.StreamerMode", "toggle");
                out.put("toggledOn", on);
                out.put("saveOn", resultOk(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", withOn)));

                // Live back to off, Hide option; the memory now disagrees with profile "on".
                Mod.staticCall("streamer.StreamerMode", "toggle");
                Mod.staticCall("streamer.StreamerMode", "setOption", hide);
                out.put("liveBefore", Mod.staticCall("streamer.StreamerMode", "active") + "/"
                        + Mod.staticCall("streamer.StreamerMode", "option"));

                out.put("applyOn", resultOk(Mod.staticCall("profiles.ProfileManager", "applyProfile", withOn)));
                out.put("afterOn", Mod.staticCall("streamer.StreamerMode", "active") + "/"
                        + Mod.staticCall("streamer.StreamerMode", "option"));
                out.put("disablesAfterOn", Mod.staticCall("streamer.StreamerMode", "disablesCheats"));

                // The next toggle must start from the profile's state (on -> off), not the stale one (off -> on).
                out.put("toggleAfterApply", Mod.staticCall("streamer.StreamerMode", "toggle"));
                out.put("fileAfterToggle", Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "");
                Mod.staticCall("streamer.StreamerMode", "toggle"); // back on, to see "none" turn it off

                out.put("applyNone", resultOk(Mod.staticCall("profiles.ProfileManager", "applyProfile", without)));
                out.put("afterNone", Mod.staticCall("streamer.StreamerMode", "active") + "/"
                        + Mod.staticCall("streamer.StreamerMode", "option"));
                out.put("fileAfterNone", Files.exists(file));
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            } finally {
                try {
                    // back to how it started: memory first, then the file exactly as it was
                    if ((Boolean) Mod.staticCall("streamer.StreamerMode", "active") != startActive) {
                        Mod.staticCall("streamer.StreamerMode", "toggle");
                    }
                    Mod.staticCall("streamer.StreamerMode", "setOption", startOption);
                    if (file != null) {
                        if (fileBefore == null) {
                            Files.deleteIfExists(file);
                        } else {
                            Files.writeString(file, fileBefore, StandardCharsets.UTF_8);
                        }
                    }
                } catch (Throwable ignored) {
                    // reported below through the end state
                }
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", withOn);
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", without);
                out.put("endActive", Mod.staticCall("streamer.StreamerMode", "active"));
            }
            return out;
        });
        if (Boolean.TRUE.equals(r.get("legit"))) {
            c.skipCase("legit jar: Streamer Mode is cheat-only (toggle refused, active() always false)");
        }
        c.note(r.toString());
        c.check(!r.containsKey("error"), "threw: " + r.get("error"));
        c.check(Boolean.TRUE.equals(r.get("toggledOn")), "Streamer Mode did not turn on: " + r);
        c.check("false/HIDE_CHEAT_UI".equals(r.get("liveBefore")), "live state before the apply was " + r.get("liveBefore"));
        c.check("true/DISABLE_CHEATS".equals(r.get("afterOn")), "applying a profile saved with Streamer Mode ON "
                + "(Disable cheats) left memory at " + r.get("afterOn") + " - StreamerMode is not reloaded by "
                + "ProfileManager.reloadAllConfigs, so it stays as it was until a restart");
        c.check(Boolean.TRUE.equals(r.get("disablesAfterOn")), "disablesCheats() is not true after the apply");
        c.check(Boolean.FALSE.equals(r.get("toggleAfterApply")), "the first toggle after the apply turned it "
                + r.get("toggleAfterApply") + " - it started from the stale state, not the profile's");
        c.check(String.valueOf(r.get("fileAfterToggle")).contains("\"active\": false"),
                "the toggle after the apply did not save active false: " + r.get("fileAfterToggle"));
        c.check(Boolean.FALSE.equals(r.get("fileAfterNone")), "applying a profile without the streamer file left it");
        c.check("false/HIDE_CHEAT_UI".equals(r.get("afterNone")), "applying a profile without the streamer file left "
                + "memory at " + r.get("afterNone") + " (should be the defaults, off / Hide cheat UI)");
        c.check(r.get("endActive").equals(r.get("startActive")), "Streamer Mode not restored: " + r.get("endActive"));
    }

    private static boolean resultOk(Object result) {
        Object ok = Mod.call(result, "success");
        if (!Boolean.TRUE.equals(ok)) {
            throw new AssertionError("ProfileManager returned failure: " + Mod.call(result, "message"));
        }
        return true;
    }
}
