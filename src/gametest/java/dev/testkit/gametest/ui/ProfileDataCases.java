package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Profiles carry everything, not just the setting files (mod profiles-all; killer560, 2026-10-13: "make sure profiles
 * also brings along everything including gifs and whatnot", and "also make sure copying profiles works going between
 * versions as well").
 * <ul>
 *   <li>980 two profiles with different GIFs, crosshair presets and sim speeds; A -> B -> A, each proven on disk (the
 *       live files are the profile's, byte for byte), in the running feature (which GIF the GIF Player holds, which
 *       one the HUD drew, its color counted on a screenshot) and in the sim speed it reads.</li>
 *   <li>981 Export then Import into a fresh profile reproduces every file byte for byte; no secret (login token, proxy
 *       password, Last.fm key, Profile Viewer API key) and no excluded cache is in the zip or the profile. A tampered
 *       copy of the zip cannot change his Auto Update channel or sharing settings (mod a8ea619e).</li>
 *   <li>982 a zip from a NEWER build (format 99, unknown setting files and keys, a damaged file, an unknown data
 *       folder, path-escape entries) imports and loads without a throw and says so; junk files are refused.</li>
 *   <li>983 a profile folder written by a build from BEFORE this change: on such a build (no ProfileData class) the
 *       case saves one and copies it to the transfer folder; on a new build it loads that folder, applies its
 *       settings, leaves the live GIFs alone, and the auto-save then stamps it.</li>
 *   <li>984 / 985 between jars: 984 exports a profile with known values (a legit setting, a cheat-only setting and a
 *       GIF) into the transfer folder; 985, on another jar, imports every zip there not made by this jar, loads it,
 *       proves the values on disk and in effect, and exports it back once, so a later run on the first jar proves
 *       cheat settings survived a trip through a legit (or testing, or 26.2) jar.</li>
 * </ul>
 * The transfer folder is {@code <checkout>/build/profile-xfer}, outside the run directory the next run rebuilds.
 */
final class ProfileDataCases {

    private ProfileDataCases() {
    }

    private static final String PM = "profiles.ProfileManager";
    private static final String GIF_FEATURE = "gifplayer.GifPlayerFeature";
    private static final String GIF_CFG = "gifplayer.GifPlayerConfig";

    static final int MAGENTA = 0xFF00FF;
    static final int GREEN = 0x00FF00;
    static final int YELLOW = 0xFFFF00;

    // =============================================================================================================
    // 980 A -> B -> A
    // =============================================================================================================

    static void switchAB(UiCase c) throws Exception {
        String a = "tk-980-a";
        String b = "tk-980-b";
        Restore restore = Restore.begin(c, "tk-980-orig");
        try {
            Path gifs = gifDir(c);
            Path presets = modPath(c, "killer560smod-crosshair-presets");
            Path speed = modPath(c, "killer560smod-sim-speed.txt");
            gifSetup(c);

            // ---- state A ----
            clearFolder(gifs);
            clearFolder(presets);
            writeGif(gifs.resolve("tk-a.gif"), MAGENTA, 64, 64);
            savePreset(c, "tk-preset-a");
            Files.writeString(speed, "432");
            String saveA = save(c, a);
            Map<String, String> liveA = hashes(gifs, presets);
            liveA.put("speed", sha(speed));
            // ---- state B ----
            clearFolder(gifs);
            clearFolder(presets);
            writeGif(gifs.resolve("tk-b.gif"), GREEN, 48, 48);
            savePreset(c, "tk-preset-b");
            Files.writeString(speed, "555");
            String saveB = save(c, b);
            Map<String, String> liveB = hashes(gifs, presets);
            liveB.put("speed", sha(speed));
            c.note("save A: " + saveA);
            c.note("save B: " + saveB);
            c.check(saveA.startsWith("true") && saveA.contains("other file"), "Save A carried no data: " + saveA);

            Path dirA = profilesDir().resolve(a);
            Path dirB = profilesDir().resolve(b);
            c.check(Files.isRegularFile(dirA.resolve("profile.json")), "profile A has no profile.json stamp");
            c.check(Files.readString(dirA.resolve("profile.json")).contains("\"format\": 2"),
                    "profile A's stamp is not format 2: " + Files.readString(dirA.resolve("profile.json")));
            c.check(Files.isRegularFile(dirA.resolve("data/killer560smod-gifs/tk-a.gif")), "A did not store tk-a.gif");
            c.check(!Files.exists(dirA.resolve("data/killer560smod-gifs/tk-b.gif")), "A stored B's GIF");
            c.check(Files.isRegularFile(dirB.resolve("data/killer560smod-gifs/tk-b.gif")), "B did not store tk-b.gif");
            c.check(!Files.exists(dirB.resolve("data/killer560smod-gifs/tk-a.gif")), "B stored A's GIF");

            String[] order = {a, b, a};
            for (int i = 0; i < order.length; i++) {
                String p = order[i];
                boolean isA = p.equals(a);
                String apply = apply(c, p);
                c.note("load " + p + ": " + apply);
                c.check(apply.startsWith("true"), "loading " + p + " failed: " + apply);
                c.ticks(20);
                // ---- on disk: the live files are this profile's, byte for byte ----
                Map<String, String> live = hashes(gifs, presets);
                live.put("speed", sha(speed));
                Map<String, String> want = isA ? liveA : liveB;
                c.note("step " + (i + 1) + " live " + live.keySet());
                c.check(live.equals(want), "after loading " + p + " the live data is " + live.keySet()
                        + ", not the profile's " + want.keySet() + " (or a file's bytes differ)");
                // ---- in effect ----
                String mine = isA ? "tk-a.gif" : "tk-b.gif";
                String other = isA ? "tk-b.gif" : "tk-a.gif";
                @SuppressWarnings("unchecked")
                List<String> playing = c.onClient(mc -> (List<String>) Mod.staticCall(GIF_FEATURE, "playingFileNames"));
                c.note("GIF Player holds " + playing);
                c.check(playing.contains(mine) && !playing.contains(other),
                        "after loading " + p + " the GIF Player holds " + playing);
                long since = c.onClient(mc -> (Long) Mod.staticCall("hud.HudSeen", "msSince",
                        "gif_player_" + mine, System.currentTimeMillis()));
                c.check(since >= 0 && since < 2000, "the HUD has not drawn " + mine + " (ms since: " + since + ")");
                int[] px = countColours(c, "step" + (i + 1) + "-" + p, MAGENTA, GREEN);
                c.note("screenshot: magenta " + px[0] + " px, green " + px[1] + " px");
                c.check(isA ? px[0] > 200 && px[1] == 0 : px[1] > 200 && px[0] == 0,
                        "the HUD shows the wrong GIF after loading " + p + ": magenta " + px[0] + ", green " + px[1]);
                int simSpeed = c.onClient(mc -> (Integer) Mod.staticCall("roomsim.SimSpeed", "saved"));
                c.check(simSpeed == (isA ? 432 : 555), "SimSpeed reads " + simSpeed + " after loading " + p);
                @SuppressWarnings("unchecked")
                List<String> presetNames = c.onClient(mc -> (List<String>) Mod.staticCall(
                        "crosshair.CustomCrosshairConfig", "listPresets"));
                c.check(presetNames.contains(isA ? "tk-preset-a" : "tk-preset-b")
                        && !presetNames.contains(isA ? "tk-preset-b" : "tk-preset-a"),
                        "crosshair presets after loading " + p + ": " + presetNames);
            }
            // The profiles themselves were not damaged by the switching (auto-save went to the right one).
            c.check(sha(dirA.resolve("data/killer560smod-gifs/tk-a.gif")).equals(liveA.get("killer560smod-gifs/tk-a.gif")),
                    "profile A's GIF changed during the switches");
            c.check(!Files.exists(dirA.resolve("data/killer560smod-gifs/tk-b.gif")), "B's GIF leaked into profile A");
            c.check(!Files.exists(dirB.resolve("data/killer560smod-gifs/tk-a.gif")), "A's GIF leaked into profile B");
        } finally {
            restore.end(c, a, b);
        }
    }

    // =============================================================================================================
    // 981 Export / Import byte for byte, no secrets
    // =============================================================================================================

    static void exportImport(UiCase c) throws Exception {
        String src = "tk-981-src";
        String imp = "tk-981-imp";
        String lock = "tk-981-lock";
        Restore restore = Restore.begin(c, "tk-981-orig");
        List<Path> planted = new ArrayList<>();
        Object viewer = Mod.cfg("profileviewer.ProfileViewerConfig");
        String oldKey = (String) Mod.call(viewer, "getApiKey");
        String fakeKey = "0f0f0f0f-1234-4abc-8def-00000000abcd";
        try {
            Path gifs = gifDir(c);
            gifSetup(c);
            clearFolder(gifs);
            writeGif(gifs.resolve("tk-x.gif"), MAGENTA, 32, 32);
            writeGif(gifs.resolve("tk-y.gif"), GREEN, 16, 16);
            savePreset(c, "tk-preset-981");
            Files.writeString(modPath(c, "killer560smod-sim-speed.txt"), "500");
            // Secrets, each where its feature keeps it.
            planted.add(plant(modPath(c, "killer560smod-session-login.json"), "{\"token\": \"tk-secret-token\"}"));
            planted.add(plant(modPath(c, "killer560smod-account-proxies.json"), "{\"password\": \"tk-secret-pass\"}"));
            planted.add(plant(modPath(c, "proxyclient.json"), "{\"password\": \"tk-secret-pass2\"}"));
            planted.add(plant(modPath(c, "spotify-lyrics-lastfm.json"), "{\"apiKey\": \"tk-secret-lastfm\"}"));
            c.onClient(mc -> {
                Mod.call(viewer, "setApiKey", fakeKey);
                Mod.call(viewer, "save");
                return null;
            });
            // Caches and logs that must stay out.
            planted.add(plant(modPath(c, "killer560smod-gifs-cache").resolve("tk-cache.wav"), "cache"));
            planted.add(plant(modPath(c, "killer560smod-roomdata").resolve("tk-room.json"), "{}"));
            planted.add(plant(modPath(c, "killer560smod-tk.log"), "log line"));
            planted.add(plant(modPath(c, "killer560smod-auction-cache.json"), "{\"tk\": 1}"));

            // A setting with no ModPaths table row lives in other/ (Mod Users is one); it must ride in the profile too
            // (mod ModPaths.settingFiles missed other/ until 2026-10-25, so an import of a zip without it grew a file).
            Path otherSetting = modPath(c, "killer560smod-modusers.json");
            c.check(otherSetting.getParent().getFileName().toString().equals("other"),
                    "Mod Users' file is no longer in other/: " + otherSetting);
            Files.writeString(otherSetting, "{\"seeOthers\": false, \"share\": false, \"shareAs\": \"legit\"}");
            String save = save(c, src);
            c.note("save: " + save);
            Path srcDir = profilesDir().resolve(src);
            c.check(Files.isRegularFile(srcDir.resolve("killer560smod-modusers.json")),
                    "the profile left out killer560smod-modusers.json, a setting file in other/");
            String export = c.onClient(mc -> HudMenuProfilesCases.resultText(Mod.staticCall(PM, "exportProfile", src)));
            c.note("export: " + export);
            Path zip = shareFolder().resolve(src + ".zip");
            c.check(Files.isRegularFile(zip), "no zip exported");
            String imported = c.onClient(mc -> HudMenuProfilesCases.resultText(
                    Mod.staticCall(PM, "importProfile", zip.toString(), imp)));
            c.note("import: " + imported);
            c.check(imported.startsWith("true"), "import failed: " + imported);
            Path impDir = profilesDir().resolve(imp);
            Map<String, String> a = tree(srcDir);
            Map<String, String> b = tree(impDir);
            c.note("source profile " + a.size() + " file(s), imported " + b.size());
            c.check(a.equals(b), "the imported profile differs from the source: only in source "
                    + diffKeys(a, b) + ", only in import " + diffKeys(b, a));
            c.check(a.containsKey("data/killer560smod-gifs/tk-x.gif") && a.containsKey("data/killer560smod-gifs/tk-y.gif"),
                    "the GIFs are not in the profile: " + a.keySet());
            c.check(a.get("data/killer560smod-gifs/tk-x.gif").equals(sha(gifs.resolve("tk-x.gif"))),
                    "the profile's GIF is not byte-identical to the live one");
            // No secrets, no caches.
            List<String> leaks = new ArrayList<>();
            List<String> entries = new ArrayList<>();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                var it = z.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    entries.add(e.getName());
                    String body = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.ISO_8859_1);
                    for (String s : new String[]{"tk-secret", fakeKey}) {
                        if (body.contains(s)) {
                            leaks.add(e.getName() + " holds " + s);
                        }
                    }
                    for (String bad : new String[]{"session-login", "account-proxies", "proxyclient", "spotify-lyrics",
                            "gifs-cache", "roomdata", ".log", "auction-cache"}) {
                        if (e.getName().contains(bad)) {
                            leaks.add("entry " + e.getName());
                        }
                    }
                }
            }
            for (Map.Entry<String, String> e : a.entrySet()) {
                String body = new String(Files.readAllBytes(srcDir.resolve(e.getKey())), StandardCharsets.ISO_8859_1);
                if (body.contains("tk-secret") || body.contains(fakeKey)) {
                    leaks.add("profile file " + e.getKey());
                }
            }
            c.note("zip: " + entries.size() + " entries, e.g. " + entries.subList(0, Math.min(6, entries.size())));
            c.check(entries.contains("profile.json"), "the zip has no profile.json stamp");
            c.check(leaks.isEmpty(), "secrets or caches in the export: " + leaks);
            importLock(c, zip, lock);
            // The secret the profile left out is still the user's own after loading the import.
            String apply = apply(c, imp);
            c.note("load import: " + apply);
            String keyNow = c.onClient(mc -> (String) Mod.call(Mod.cfg("profileviewer.ProfileViewerConfig"), "getApiKey"));
            c.check(fakeKey.equals(keyNow), "loading the import lost the live API key (now '" + keyNow + "')");
            c.check(Files.readString(modPath(c, "killer560smod-session-login.json")).contains("tk-secret-token"),
                    "loading a profile touched the session login file");
        } finally {
            c.onClient(mc -> {
                Mod.call(viewer, "setApiKey", oldKey == null ? "" : oldKey);
                Mod.call(viewer, "save");
                return null;
            });
            for (Path p : planted) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(shareFolder().resolve(src + ".zip"));
            restore.end(c, src, imp, lock);
        }
    }

    /**
     * Mod a8ea619e: an imported zip never changes his Auto Update channel or sharing settings. A copy of {@code zip} with
     * Auto Update's channel and switch and Supporters' two share switches changed (plus one Supporters key that is NOT
     * locked), and Mod Users left out, is imported into {@code name}: the locked keys must read his live values, the
     * free key the zip's, and the left-out Mod Users file must be his live share settings.
     */
    private static void importLock(UiCase c, Path zip, String name) throws Exception {
        String au = "killer560smod-autoupdate.json";
        String sup = "killer560smod-supporters.json";
        String mu = "killer560smod-modusers.json";
        // Known live values, planted (a fresh run dir has none of these files; Restore.end puts his back).
        com.google.gson.JsonObject liveAu = json("{\"channel\": \"FULL_RELEASE\", \"enabled\": true}");
        com.google.gson.JsonObject liveSup = json("{\"showSupporterNames\": false, \"shareIfSupporter\": false, "
                + "\"shareTrail\": true}");
        com.google.gson.JsonObject liveMu = json("{\"seeOthers\": false, \"share\": false, \"shareAs\": \"legit\"}");
        Files.writeString(modPath(c, au), liveAu.toString());
        Files.writeString(modPath(c, sup), liveSup.toString());
        Files.writeString(modPath(c, mu), liveMu.toString());
        Path tampered = Files.createTempFile("tk-981-lock", ".zip");
        try {
            try (ZipFile in = new ZipFile(zip.toFile());
                 OutputStream os = Files.newOutputStream(tampered); ZipOutputStream out = new ZipOutputStream(os)) {
                var it = in.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    if (e.getName().equals(au) || e.getName().equals(sup) || e.getName().equals(mu)) {
                        continue;
                    }
                    put(out, e.getName(), in.getInputStream(e).readAllBytes());
                }
                // Every locked key the other way round, one free key flipped too, and Mod Users left out.
                put(out, au, "{\"channel\": \"TK_TAMPERED\", \"enabled\": false}");
                put(out, sup, "{\"showSupporterNames\": true, \"shareIfSupporter\": true, \"shareTrail\": false}");
            }
            String imported = c.onClient(mc -> HudMenuProfilesCases.resultText(
                    Mod.staticCall(PM, "importProfile", tampered.toString(), name)));
            c.check(imported.startsWith("true"), "the tampered zip did not import: " + imported);
            Path dir = profilesDir().resolve(name);
            com.google.gson.JsonObject gotAu = json(Files.readString(dir.resolve(au)));
            com.google.gson.JsonObject gotSup = json(Files.readString(dir.resolve(sup)));
            Path muFile = dir.resolve(mu);
            com.google.gson.JsonObject gotMu = Files.isRegularFile(muFile) ? json(Files.readString(muFile)) : null;
            List<String> wrong = new ArrayList<>();
            for (String k : List.of("channel", "enabled")) {
                if (!liveAu.get(k).equals(gotAu.get(k))) {
                    wrong.add("autoupdate " + k + " " + gotAu.get(k) + " (live " + liveAu.get(k) + ")");
                }
            }
            for (String k : List.of("shareIfSupporter", "shareTrail")) {
                if (!liveSup.get(k).equals(gotSup.get(k))) {
                    wrong.add("supporters " + k + " " + gotSup.get(k) + " (live " + liveSup.get(k) + ")");
                }
            }
            boolean freeKey = true;
            if (gotSup.get("showSupporterNames") == null || gotSup.get("showSupporterNames").getAsBoolean() != freeKey) {
                wrong.add("supporters showSupporterNames " + gotSup.get("showSupporterNames") + " (the zip's " + freeKey
                        + ": an unlocked key must keep the zip's value)");
            }
            com.google.gson.JsonObject wantMu = json("{\"share\": false, \"shareAs\": \"legit\"}");
            if (!wantMu.equals(gotMu)) {
                wrong.add("modusers (left out of the zip) " + gotMu + ", want exactly " + wantMu);
            }
            c.note("tampered import: " + imported + "; locked keys " + (wrong.isEmpty() ? "all his live values" : wrong));
            c.check(wrong.isEmpty(), "an imported zip changed locked settings: " + wrong);
        } finally {
            Files.deleteIfExists(tampered);
        }
    }

    private static com.google.gson.JsonObject json(String s) {
        return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
    }

    // =============================================================================================================
    // 982 a zip from another (newer, damaged, hostile) build
    // =============================================================================================================

    static void importRobust(UiCase c) throws Exception {
        String name = "tk-982-future";
        Restore restore = Restore.begin(c, "tk-982-orig");
        Path tmp = Files.createTempDirectory("tk-982");
        Path newData = null;
        try {
            Path zip = tmp.resolve("future.zip");
            try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
                put(z, "profile.json", "{\"format\": 99, \"modVersion\": \"9.9.9\", \"minecraft\": \"99.1\", "
                        + "\"variant\": \"cheat\", \"newField\": [1, 2]}");
                put(z, "killer560smod-gifplayer.json", "{\"enabled\": true, \"speedMultiplier\": 1.25, "
                        + "\"someFutureKey\": {\"x\": 1}}");
                put(z, "killer560smod-tk-future-feature.json", "{\"on\": true}");
                put(z, "killer560smod-tk-broken.json", "{ this is not json");
                put(z, "data/killer560smod-gifs/tk-future.gif", gifBytes(YELLOW, 20, 20));
                put(z, "data/killer560smod-tk-new-feature/notes/tk.txt", "hello from the future");
                put(z, "data/killer560smod-gifs/../../../tk-evil.txt", "escape");
                put(z, "../tk-evil2.txt", "escape");
                put(z, "data/killer560smod-voice-model/model.bin", "excluded");
                put(z, "data/killer560smod-session-login.json", "{\"token\": \"tk-secret\"}");
            }
            String imported = c.onClient(mc -> HudMenuProfilesCases.resultText(
                    Mod.staticCall(PM, "importProfile", zip.toString(), name)));
            c.note("import: " + imported);
            c.check(imported.startsWith("true"), "the future profile did not import: " + imported);
            c.check(imported.contains("newer"), "no 'newer mod version' note: " + imported);
            c.check(imported.contains("damaged"), "the damaged file was not reported: " + imported);
            Path dir = profilesDir().resolve(name);
            Map<String, String> files = tree(dir);
            c.note("imported profile: " + files.keySet());
            c.check(files.containsKey("data/killer560smod-gifs/tk-future.gif"), "the GIF was not imported");
            c.check(files.containsKey("data/killer560smod-tk-new-feature/notes/tk.txt"),
                    "an unknown (future) data folder was dropped");
            c.check(!files.containsKey("killer560smod-tk-broken.json"), "the damaged file was imported");
            c.check(files.keySet().stream().noneMatch(k -> k.contains("voice-model") || k.contains("session-login")
                    || k.contains("evil")), "excluded or escaping entries were imported: " + files.keySet());
            List<Path> evil = new ArrayList<>();
            for (Path root : new Path[]{profilesDir(), profilesDir().getParent(), modRoot(c), modRoot(c).getParent()}) {
                for (String n : new String[]{"tk-evil.txt", "tk-evil2.txt"}) {
                    if (Files.exists(root.resolve(n))) {
                        evil.add(root.resolve(n));
                    }
                }
            }
            c.check(evil.isEmpty(), "a path-escape entry was written: " + evil);
            String apply = apply(c, name);
            c.note("load: " + apply);
            c.check(apply.startsWith("true"), "loading the future profile failed: " + apply);
            c.check(apply.contains("newer"), "loading did not say the profile is from a newer version: " + apply);
            c.ticks(10);
            float speed = c.onClient(mc -> (Float) Mod.get(GIF_CFG, "getSpeedMultiplier"));
            c.check(Math.abs(speed - 1.25f) < 1e-4, "the future gifplayer settings did not apply (speed " + speed + ")");
            @SuppressWarnings("unchecked")
            List<String> playing = c.onClient(mc -> (List<String>) Mod.staticCall(GIF_FEATURE, "playingFileNames"));
            c.check(playing.contains("tk-future.gif"), "the future profile's GIF is not playing: " + playing);
            newData = modPath(c, "killer560smod-tk-new-feature");
            c.check(Files.isRegularFile(newData.resolve("notes/tk.txt")),
                    "the unknown data folder was not restored at " + newData);
            // Junk is refused with a message, never a throw.
            Path junk = tmp.resolve("junk.zip");
            Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
            String junkResult = c.onClient(mc -> HudMenuProfilesCases.resultText(
                    Mod.staticCall(PM, "importProfile", junk.toString(), "tk-982-junk")));
            c.note("junk: " + junkResult);
            c.check(junkResult.startsWith("false"), "a junk file imported: " + junkResult);
            c.check(!Files.exists(profilesDir().resolve("tk-982-junk")), "a junk import left a profile behind");
        } finally {
            if (newData != null) {
                deleteTree(newData);
            }
            deleteTree(tmp);
            restore.end(c, name, "tk-982-junk");
        }
    }

    // =============================================================================================================
    // 983 a profile folder from a build before this change
    // =============================================================================================================

    static void oldFormat(UiCase c) throws Exception {
        Path old = xferDir().resolve("old-profile");
        if (!Mod.has("profiles.ProfileData")) {
            // Producer: this IS a build from before profiles carried data. Save a profile with known values.
            String name = "tk-983-old";
            Object cfg = Mod.cfg(GIF_CFG);
            float before = (Float) Mod.call(cfg, "getSpeedMultiplier");
            try {
                c.onClient(mc -> {
                    Mod.call(cfg, "setSpeedMultiplier", 2.5f);
                    Mod.call(cfg, "save");
                    return null;
                });
                String save = save(c, name);
                c.note("old build save: " + save);
                deleteTree(old);
                copyTree(profilesDir().resolve(name), old);
                c.note("copied the old build's profile folder to " + old + ": " + tree(old).size() + " file(s)");
                c.check(!Files.exists(old.resolve("profile.json")), "an old build wrote a profile.json?");
            } finally {
                c.onClient(mc -> {
                    Mod.call(cfg, "setSpeedMultiplier", before);
                    Mod.call(cfg, "save");
                    Mod.staticCall(PM, "deleteProfile", name);
                    return null;
                });
            }
            return;
        }
        if (!Files.isDirectory(old)) {
            // The old folder only exists after 983 ran on a pre-profiles-all jar from this checkout; without one there
            // is nothing to load, which is a missing input, not a mod fault.
            c.skipCase("no old-build profile at " + old + " - run 983 on a jar from before profiles-all first");
        }
        String name = "tk-983-old-in";
        Restore restore = Restore.begin(c, "tk-983-orig");
        try {
            Path gifs = gifDir(c);
            gifSetup(c);
            clearFolder(gifs);
            writeGif(gifs.resolve("tk-live.gif"), MAGENTA, 40, 40);
            String liveSha = sha(gifs.resolve("tk-live.gif"));
            Path dir = profilesDir().resolve(name);
            copyTree(old, dir);
            String fileJson = Files.readString(dir.resolve("killer560smod-gifplayer.json"));
            c.note("old profile: " + tree(dir).size() + " file(s); its gifplayer: " + fileJson.replaceAll("\\s+", " "));
            c.check(fileJson.contains("2.5"), "the old profile does not hold the producer's speed 2.5");
            String apply = apply(c, name);
            c.note("load: " + apply);
            c.check(apply.startsWith("true"), "loading the old profile failed: " + apply);
            c.ticks(10);
            float speed = c.onClient(mc -> (Float) Mod.get(GIF_CFG, "getSpeedMultiplier"));
            c.check(Math.abs(speed - 2.5f) < 1e-4, "the old profile's GIF speed did not apply (" + speed + ")");
            c.check(Files.isRegularFile(gifs.resolve("tk-live.gif")) && sha(gifs.resolve("tk-live.gif")).equals(liveSha),
                    "loading an old profile touched the live GIFs");
            @SuppressWarnings("unchecked")
            List<String> playing = c.onClient(mc -> (List<String>) Mod.staticCall(GIF_FEATURE, "playingFileNames"));
            c.check(playing.contains("tk-live.gif"), "the live GIF stopped playing: " + playing);
            // The auto-save brings it up to date: stamped, and now carrying the live data.
            c.onClient(mc -> {
                Mod.staticCall(PM, "syncActiveProfile");
                return null;
            });
            c.check(Files.isRegularFile(dir.resolve("profile.json")), "the auto-save did not stamp the old profile");
            c.check(Files.isRegularFile(dir.resolve("data/killer560smod-gifs/tk-live.gif")),
                    "the auto-save did not add the live GIF to the old profile");
        } finally {
            restore.end(c, name);
        }
    }

    // =============================================================================================================
    // 984 / 985 between jars
    // =============================================================================================================

    static final float XFER_SPEED = 1.75f;
    static final int XFER_WARPS = 7;

    static void xferExport(UiCase c) throws Exception {
        String name = "tk-984-xfer";
        Restore restore = Restore.begin(c, "tk-984-orig");
        try {
            Path gifs = gifDir(c);
            gifSetup(c);
            clearFolder(gifs);
            writeGif(gifs.resolve("tk-xfer.gif"), YELLOW, 40, 40);
            c.onClient(mc -> {
                Object g = Mod.cfg(GIF_CFG);
                Mod.call(g, "setSpeedMultiplier", XFER_SPEED);
                Mod.call(g, "save");
                Object ar = Mod.cfg("autoroutes.AutoRoutesConfig");
                Mod.call(ar, "setEtherwarpsPerSecond", XFER_WARPS);
                Mod.call(ar, "save");
                return null;
            });
            String save = save(c, name);
            c.note("save: " + save);
            String export = c.onClient(mc -> HudMenuProfilesCases.resultText(Mod.staticCall(PM, "exportProfile", name)));
            c.note("export: " + export);
            Path zip = shareFolder().resolve(name + ".zip");
            c.check(Files.isRegularFile(zip), "no zip");
            Path target = xferDir().resolve(tag() + ".zip");
            Files.createDirectories(target.getParent());
            Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String meta;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                meta = new String(z.getInputStream(z.getEntry("profile.json")).readAllBytes(), StandardCharsets.UTF_8);
            }
            c.note("wrote " + target + "; stamp " + meta.replaceAll("\\s+", " "));
            c.check(meta.contains("\"variant\": \"" + (Mod.isCheat() ? "cheat" : "legit") + "\""),
                    "the stamp does not name this jar's variant");
            Files.deleteIfExists(zip);
        } finally {
            restore.end(c, name);
        }
    }

    static void xferImport(UiCase c) throws Exception {
        String own = tag();
        List<Path> zips = new ArrayList<>();
        if (Files.isDirectory(xferDir())) {
            try (Stream<Path> s = Files.list(xferDir())) {
                s.filter(p -> p.getFileName().toString().endsWith(".zip"))
                        // not this jar's own export or re-export ("26.1.2-cheat" must not swallow "26.1.2-cheat-testing")
                        .filter(p -> !p.getFileName().toString().equals(own + ".zip")
                                && !p.getFileName().toString().startsWith(own + "__"))
                        .sorted().forEach(zips::add);
            }
        }
        c.note("this jar: " + own + "; zips from other jars: " + zips.stream().map(p -> p.getFileName().toString()).toList());
        if (zips.isEmpty()) {
            // Another jar's 984 must have run from this checkout first; a fresh checkout has none to import.
            c.skipCase("no profile from another jar in " + xferDir() + " - run 984 on another jar first");
        }
        Restore restore = Restore.begin(c, "tk-985-orig");
        List<String> made = new ArrayList<>();
        try {
            Path gifs = gifDir(c);
            Path arFile = modPath(c, "killer560smod-autoroutes-settings.json");
            for (int i = 0; i < zips.size(); i++) {
                Path zip = zips.get(i);
                String src = zip.getFileName().toString().replace(".zip", "");
                String name = "tk-985-" + i;
                made.add(name);
                String imported = c.onClient(mc -> HudMenuProfilesCases.resultText(
                        Mod.staticCall(PM, "importProfile", zip.toString(), name)));
                c.note(src + " import: " + imported);
                c.check(imported.startsWith("true"), src + ": import failed: " + imported);
                String apply = apply(c, name);
                c.note(src + " load: " + apply);
                c.check(apply.startsWith("true"), src + ": load failed: " + apply);
                c.ticks(20);
                // a legit setting, on disk and in effect
                float speed = c.onClient(mc -> (Float) Mod.get(GIF_CFG, "getSpeedMultiplier"));
                c.check(Math.abs(speed - XFER_SPEED) < 1e-4, src + ": GIF speed " + speed + ", not " + XFER_SPEED);
                // the GIF, on disk and on the HUD
                c.check(Files.isRegularFile(gifs.resolve("tk-xfer.gif")), src + ": tk-xfer.gif is not in the GIF folder");
                @SuppressWarnings("unchecked")
                List<String> playing = c.onClient(mc -> (List<String>) Mod.staticCall(GIF_FEATURE, "playingFileNames"));
                c.check(playing.contains("tk-xfer.gif"), src + ": tk-xfer.gif is not playing: " + playing);
                int[] px = countColours(c, "xfer-" + i, YELLOW);
                c.note(src + ": GIF Player holds " + playing + "; yellow " + px[0] + " px on screen");
                c.check(px[0] > 100, src + ": the imported GIF is not on the HUD (yellow " + px[0] + " px)");
                // the cheat-only setting: on disk on every jar, in effect on a cheat jar
                String ar = Files.isRegularFile(arFile) ? Files.readString(arFile) : "";
                c.check(ar.contains("\"etherwarpsPerSecond\": " + XFER_WARPS),
                        src + ": the cheat-only setting is not on disk after the load: " + ar.replaceAll("\\s+", " "));
                int warps = c.onClient(mc -> (Integer) Mod.get("autoroutes.AutoRoutesConfig", "getEtherwarpsPerSecond"));
                c.note(src + ": Auto Routes etherwarps/s " + warps + (Mod.isCheat() ? " (in effect)" : " (inert on legit)"));
                c.check(warps == XFER_WARPS, src + ": Auto Routes reads " + warps + ", not " + XFER_WARPS);
                // one hop back: export what this jar now holds for the next jar to import
                if (!src.contains("__from__")) {
                    c.ticks(50); // two auto-save polls: whatever this jar re-saved goes into the profile first
                    String export = c.onClient(mc -> HudMenuProfilesCases.resultText(
                            Mod.staticCall(PM, "exportProfile", name)));
                    Path out = shareFolder().resolve(name + ".zip");
                    Path target = xferDir().resolve(own + "__from__" + src + ".zip");
                    Files.copy(out, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(out);
                    c.note(src + ": re-exported as " + target.getFileName() + " (" + export + ")");
                }
            }
        } finally {
            restore.end(c, made.toArray(new String[0]));
        }
    }

    // =============================================================================================================
    // shared
    // =============================================================================================================

    /** Snapshots the live state into a profile first, and at the end loads it back and deletes every profile made. */
    static final class Restore {
        final String orig;
        final String activeBefore;
        final Path marker;

        private Restore(String orig, String activeBefore, Path marker) {
            this.orig = orig;
            this.activeBefore = activeBefore;
            this.marker = marker;
        }

        static Restore begin(UiCase c, String orig) {
            String active = c.onClient(mc -> (String) Mod.staticCall(PM, "getActiveProfile"));
            Path marker = (Path) R.getStatic(R.cls(PM), "ACTIVE_MARKER");
            String saved = save(c, orig);
            c.check(saved.startsWith("true"), "could not snapshot the live settings: " + saved);
            return new Restore(orig, active, marker);
        }

        void end(UiCase c, String... made) {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(PM, "applyProfile", orig);
                for (String p : made) {
                    Mod.staticCall(PM, "deleteProfile", p);
                }
                Mod.staticCall(PM, "deleteProfile", orig);
                try {
                    if (activeBefore == null) {
                        Files.deleteIfExists(marker);
                    } else {
                        Files.writeString(marker, activeBefore);
                    }
                } catch (Exception ignored) {
                    // nothing later reads it
                }
                if (Quiet.enabled()) {
                    Quiet.apply();
                }
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
            c.ticks(2);
        }
    }

    static String save(UiCase c, String name) {
        return c.onClient(mc -> HudMenuProfilesCases.resultText(Mod.staticCall(PM, "saveCurrentAsProfile", name)));
    }

    static String apply(UiCase c, String name) {
        return c.onClient(mc -> HudMenuProfilesCases.resultText(Mod.staticCall(PM, "applyProfile", name)));
    }

    static Path profilesDir() {
        return (Path) R.getStatic(R.cls(PM), "PROFILES_DIR");
    }

    static Path shareFolder() {
        return (Path) Mod.staticCall(PM, "shareFolder");
    }

    static Path modPath(UiCase c, String name) {
        return (Path) Mod.staticCall("util.ModPaths", "config", name);
    }

    static Path modRoot(UiCase c) {
        return (Path) Mod.staticCall("util.ModPaths", "root");
    }

    static Path gifDir(UiCase c) {
        return (Path) Mod.staticCall(GIF_FEATURE, "folder");
    }

    /** GIF Player on, the built-in GIF off (so only the test's GIFs draw), saved. */
    static void gifSetup(UiCase c) {
        String builtin = (String) Mod.field(GIF_FEATURE, "BUILTIN_NAME");
        c.onClient(mc -> {
            Object g = Mod.cfg(GIF_CFG);
            Mod.call(g, "setEnabled", true);
            Mod.call(g, "setGifFileEnabled", builtin, false);
            Mod.call(g, "save");
            return null;
        });
    }

    static void savePreset(UiCase c, String name) {
        c.onClient(mc -> Mod.call(Mod.cfg("crosshair.CustomCrosshairConfig"), "savePreset", name));
    }

    /** {@code <checkout>/build/profile-xfer}: outside the run directory, shared by every jar run from this checkout. */
    static Path xferDir() {
        String checkout = System.getProperty("testkit.checkout");
        return Path.of(checkout == null ? "." : checkout).resolve("build").resolve("profile-xfer");
    }

    /** {@code <minecraft>-<variant>[-testing]} of the running jar. */
    static String tag() {
        String mc = (String) Mod.staticCall("autoupdate.BuildInfo", "minecraft");
        boolean testing = false;
        try {
            testing = (Boolean) Mod.field("BuildVariant", "TESTING");
        } catch (Throwable ignored) {
            // an older jar without the testing flag
        }
        return (mc == null ? "unknown" : mc) + "-" + (Mod.isCheat() ? "cheat" : "legit") + (testing ? "-testing" : "");
    }

    static Path plant(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    static void writeGif(Path file, int rgb, int w, int h) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, gifBytes(rgb, w, h));
    }

    static byte[] gifBytes(int rgb, int w, int h) throws Exception {
        byte[] r = {(byte) (rgb >> 16), 0};
        byte[] g = {(byte) (rgb >> 8), 0};
        byte[] b = {(byte) rgb, 0};
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(1, 2, r, g, b));
        // every pixel index 0 = the color
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(img, "gif", out)) {
            throw new AssertionError("no GIF writer");
        }
        return out.toByteArray();
    }

    static void put(ZipOutputStream z, String name, String body) throws Exception {
        put(z, name, body.getBytes(StandardCharsets.UTF_8));
    }

    static void put(ZipOutputStream z, String name, byte[] body) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(body);
        z.closeEntry();
    }

    static void clearFolder(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                deleteTree(p);
            }
        }
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(q);
            }
        }
    }

    static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** Relative path (forward slashes) -> sha of every file under {@code dir}. */
    static Map<String, String> tree(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), sha(p));
            }
        }
        return out;
    }

    /** Both folders' files, keyed "<folder name>/<file>". */
    static Map<String, String> hashes(Path... dirs) throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Path d : dirs) {
            for (Map.Entry<String, String> e : tree(d).entrySet()) {
                out.put(d.getFileName() + "/" + e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static List<String> diffKeys(Map<String, String> a, Map<String, String> b) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!e.getValue().equals(b.get(e.getKey()))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Pixels within 20 of each exact color on a fresh screenshot (no screen open). */
    static int[] countColours(UiCase c, String shot, int... colours) throws Exception {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(5);
        Path taken = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + shot));
        Report.screenshot(c.name() + "-" + shot, taken);
        BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        int[] counts = new int[colours.length];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y) & 0xFFFFFF;
                for (int i = 0; i < colours.length; i++) {
                    int q = colours[i];
                    if (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) <= 20
                            && Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) <= 20
                            && Math.abs((p & 255) - (q & 255)) <= 20) {
                        counts[i]++;
                    }
                }
            }
        }
        return counts;
    }
}
