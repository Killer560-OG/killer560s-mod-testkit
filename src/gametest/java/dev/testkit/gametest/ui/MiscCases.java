package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Cases 320 (profiles), 330 (ModPaths.migrateAll), 340 (main menu theme), 350 (termism), 360 (client visuals),
 * 370 (deny lists: no OS window, no child process, hooks in force).
 */
final class MiscCases {

    private MiscCases() {
    }

    // ---- 320 profiles ---------------------------------------------------------------------------------------

    record Probe(String rel, Method getter, Method setter, boolean original) {
    }

    /**
     * Profiles, end to end through profiles/ProfileManager: one boolean setting of every (non-denied) config flipped
     * and saved -> saveCurrentAsProfile(A) -> every one flipped back -> exportProfile(A) -> importProfile(zip, B) ->
     * applyProfile(B). After the apply, each config must READ the flipped value: applyProfile rewrites the files and
     * then reloads "every in-memory config" from a hand-kept list (ProfileManager.reloadAllConfigs, line 316); a
     * config missing from that list keeps its old value in memory and writes it back over the profile on its next
     * save. The file on disk is checked too, so "the profile did not carry it" and "it was not reloaded" are told
     * apart.
     */
    static void profiles(UiCase c) {
        String a = "wp4-probe-a";
        String b = "wp4-probe-b";
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Probe> probes = new ArrayList<>();
            Class<?> pm = R.cls("profiles.ProfileManager");
            try {
                for (ConfigSweep.Singleton s : ConfigSweep.singletons(c)) {
                    if (ConfigSweep.SETTER_DENY.contains(s.rel())) {
                        continue;
                    }
                    Probe p = probe(s);
                    if (p != null) {
                        probes.add(p);
                    }
                }
                // state A: every probe flipped
                for (Probe p : probes) {
                    setAndSave(p, !p.original());
                }
                out.put("save", resultText(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", a)));
                for (Probe p : probes) {
                    setAndSave(p, p.original());
                }
                out.put("export", resultText(Mod.staticCall("profiles.ProfileManager", "exportProfile", a)));
                Path dir = (Path) R.getStatic(pm, "PROFILES_DIR");
                Path zip = dir.resolve(a + ".zip");
                out.put("zipBytes", Files.isRegularFile(zip) ? Files.size(zip) : -1L);
                out.put("import", resultText(Mod.staticCall("profiles.ProfileManager", "importProfile",
                        zip.toString(), b)));
                long filesA;
                long filesB;
                try (Stream<Path> sa = Files.list(dir.resolve(a)); Stream<Path> sb = Files.list(dir.resolve(b))) {
                    filesA = sa.count();
                    filesB = sb.count();
                }
                out.put("filesA", filesA);
                out.put("filesB", filesB);
                @SuppressWarnings("unchecked")
                List<String> listed = (List<String>) Mod.staticCall("profiles.ProfileManager", "listProfiles");
                out.put("listed", listed.contains(a) && listed.contains(b));
                out.put("apply", resultText(Mod.staticCall("profiles.ProfileManager", "applyProfile", b)));
                List<String> stale = new ArrayList<>();
                List<String> notCarried = new ArrayList<>();
                int ok = 0;
                for (Probe p : probes) {
                    Object inst = ConfigSweep.instance(R.cls(p.rel()));
                    boolean now = (Boolean) ConfigSweep.invoke(p.getter(), inst);
                    if (now != p.original()) {
                        ok++;
                        continue;
                    }
                    Path file = ConfigSweep.configPath(R.cls(p.rel()));
                    String onDisk = file != null && Files.isRegularFile(file) ? ConfigSweep.read(file) : "";
                    String key = jsonKeyGuess(p);
                    boolean diskFlipped = onDisk.contains("\"" + key + "\": " + !p.original());
                    (diskFlipped ? stale : notCarried).add(p.rel() + "." + p.getter().getName()
                            + (file == null ? "" : " (" + file.getFileName() + ")"));
                }
                out.put("probes", probes.size());
                out.put("ok", ok);
                out.put("stale", stale);
                out.put("notCarried", notCarried);
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            } finally {
                for (Probe p : probes) {
                    try {
                        setAndSave(p, p.original());
                    } catch (Throwable ignored) {
                        // reported through the verdict below if it matters
                    }
                }
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", a);
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", b);
                try {
                    Files.deleteIfExists(((Path) R.getStatic(pm, "PROFILES_DIR")).resolve(a + ".zip"));
                } catch (Exception ignored) {
                    // a leftover zip in a wiped run dir
                }
            }
            return out;
        });
        c.check(!r.containsKey("error"), "profiles threw: " + r.get("error"));
        c.note("save: " + r.get("save"));
        c.note("export: " + r.get("export") + " (" + r.get("zipBytes") + " bytes)");
        c.note("import: " + r.get("import") + "; files in A " + r.get("filesA") + ", in B " + r.get("filesB"));
        c.note("apply: " + r.get("apply"));
        c.note(r.get("probes") + " configs probed with one boolean each; " + r.get("ok")
                + " read the profile's value after applyProfile");
        c.check(Boolean.TRUE.equals(r.get("listed")), "listProfiles does not show both " + a + " and " + b);
        c.check(((Long) r.get("zipBytes")) > 0, "exportProfile wrote no zip");
        c.check(r.get("filesA").equals(r.get("filesB")), "import of the exported zip has " + r.get("filesB")
                + " files, the profile it came from " + r.get("filesA"));
        @SuppressWarnings("unchecked")
        List<String> stale = (List<String>) r.get("stale");
        @SuppressWarnings("unchecked")
        List<String> notCarried = (List<String>) r.get("notCarried");
        if (!stale.isEmpty()) {
            c.problem("applyProfile wrote the profile's value to disk but these configs still hold the OLD value in "
                    + "memory (not reloaded by ProfileManager.reloadAllConfigs, profiles/ProfileManager.java:316; their "
                    + "next save() writes the old value back over the profile): " + stale);
        }
        if (!notCarried.isEmpty()) {
            c.note("value not on disk after apply either (file not part of profiles, or key named differently): "
                    + notCarried);
        }
        c.check((Integer) r.get("probes") > 80, "only " + r.get("probes") + " configs probed");
    }

    private static Probe probe(ConfigSweep.Singleton s) throws Throwable {
        Object inst = ConfigSweep.instance(s.cls());
        for (Method setter : inst.getClass().getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1
                    || setter.getParameterTypes()[0] != boolean.class) {
                continue;
            }
            String prop = setter.getName().substring(3);
            Method getter;
            try {
                getter = inst.getClass().getMethod("is" + prop);
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (getter.getReturnType() != boolean.class) {
                continue;
            }
            boolean old = (Boolean) ConfigSweep.invoke(getter, inst);
            ConfigSweep.invoke(setter, inst, !old);
            boolean took = (Boolean) ConfigSweep.invoke(getter, inst) != old;
            ConfigSweep.invoke(setter, inst, old);
            if (took) {
                return new Probe(s.rel(), getter, setter, old);
            }
        }
        return null;
    }

    private static void setAndSave(Probe p, boolean v) throws Throwable {
        Object inst = ConfigSweep.instance(R.cls(p.rel()));
        ConfigSweep.invoke(p.setter(), inst, v);
        ConfigSweep.save(inst);
    }

    private static String jsonKeyGuess(Probe p) {
        String prop = p.getter().getName().substring(2);
        return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
    }

    private static String resultText(Object result) {
        Object ok = Mod.call(result, "success");
        Object msg = Mod.call(result, "message");
        String text = net.minecraft.ChatFormatting.stripFormatting(String.valueOf(msg));
        if (!Boolean.TRUE.equals(ok)) {
            throw new AssertionError("[ui] ProfileManager returned failure: " + text);
        }
        return text;
    }

    // ---- 330 migrate ----------------------------------------------------------------------------------------

    /**
     * util/ModPaths.migrateAll over legacy files seeded in the config ROOT. Expected folders are read from the
     * prefix table in util/ModPaths.java (line cited per row), not from ModPaths itself, so a table edit that moves
     * a feature shows up here.
     */
    static void migrate(UiCase c) throws Exception {
        Path config = FabricLoader.getInstance().getConfigDir();
        Path root = config.resolve("killer560");
        // {legacy name in config/, expected path under config/killer560/, source line}
        String[][] rows = {
                {"killer560smod-ap3-wp4probe.json", "dungeons/ap3/killer560smod-ap3-wp4probe.json", "ModPaths.java:71"},
                {"killer560smod-runstats-wp4probe.json", "dungeons/runs/killer560smod-runstats-wp4probe.json",
                        "ModPaths.java:110 (\"run\" prefix)"},
                {"killer560smod-hud-wp4probe.json", "interface/hud/killer560smod-hud-wp4probe.json", "ModPaths.java:168"},
                {"killer560smod-terminalwp4probe.json", "dungeons/terminals/killer560smod-terminalwp4probe.json",
                        "ModPaths.java:88"},
                {"killer560smod-zzwp4probe.json", "other/killer560smod-zzwp4probe.json", "ModPaths.java:57 (OTHER)"},
        };
        String legacyDirChild = "wp4probe-route.json";
        String foreign = "othermod-wp4probe.json";
        List<Path> cleanup = new ArrayList<>();
        try {
            for (String[] row : rows) {
                Files.writeString(config.resolve(row[0]), "{\"wp4\": \"" + row[0] + "\"}", StandardCharsets.UTF_8);
            }
            // a folder of ours
            Path dir = config.resolve("killer560smod-zzwp4probe-dir");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("inside.json"), "{\"wp4\": \"dir\"}", StandardCharsets.UTF_8);
            // AP3's old folder: its CONTENTS move into dungeons/ap3 and the emptied folder goes (ModPaths.java:422)
            Path legacyAp3 = config.resolve("killer560smod");
            boolean legacyExisted = Files.isDirectory(legacyAp3);
            Files.createDirectories(legacyAp3);
            Files.writeString(legacyAp3.resolve(legacyDirChild), "{\"wp4\": \"route\"}", StandardCharsets.UTF_8);
            // not ours: must stay where it is (ModPaths.isOurs, line 381)
            Files.writeString(config.resolve(foreign), "{}", StandardCharsets.UTF_8);
            // a conflict: the tree already has hud.json; a root copy must NOT overwrite it
            Path liveHud = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-hud.json");
            Mod.call(Mod.cfg("hud.HudConfig"), "save");
            String liveHudBefore = Files.readString(liveHud, StandardCharsets.UTF_8);
            Files.writeString(config.resolve("killer560smod-hud.json"), "{\"wp4\": \"stale root copy\"}",
                    StandardCharsets.UTF_8);
            cleanup.add(config.resolve("killer560smod-hud.json"));
            cleanup.add(config.resolve(foreign));

            c.onClient(mc -> {
                Mod.staticCall("util.ModPaths", "migrateAll");
                return null;
            });

            for (String[] row : rows) {
                Path moved = root.resolve(row[1]);
                cleanup.add(moved);
                if (Files.exists(config.resolve(row[0]))) {
                    c.problem(row[0] + " is still in the config root after migrateAll");
                }
                if (!Files.isRegularFile(moved)) {
                    c.problem(row[0] + " did not arrive at killer560/" + row[1] + " (" + row[2] + ")");
                } else if (!Files.readString(moved, StandardCharsets.UTF_8).contains(row[0])) {
                    c.problem(row[0] + " arrived at " + row[1] + " with different content");
                }
            }
            Path movedDir = root.resolve("other/killer560smod-zzwp4probe-dir");
            cleanup.add(movedDir.resolve("inside.json"));
            cleanup.add(movedDir);
            if (!Files.isRegularFile(movedDir.resolve("inside.json")) || Files.exists(dir)) {
                c.problem("folder killer560smod-zzwp4probe-dir did not move whole to killer560/other/");
            }
            Path movedChild = root.resolve("dungeons/ap3/" + legacyDirChild);
            cleanup.add(movedChild);
            if (!Files.isRegularFile(movedChild)) {
                c.problem("config/killer560smod/" + legacyDirChild + " did not move into killer560/dungeons/ap3/");
            }
            if (!legacyExisted && Files.exists(legacyAp3)) {
                c.problem("the emptied config/killer560smod/ folder was left behind (ModPaths.java:441-445 deletes it)");
            }
            if (!Files.isRegularFile(config.resolve(foreign))) {
                c.problem("another mod's file " + foreign + " was moved or deleted - migrateAll must only move ours");
            }
            String liveHudAfter = Files.readString(liveHud, StandardCharsets.UTF_8);
            if (!liveHudAfter.equals(liveHudBefore)) {
                c.problem("a stale root killer560smod-hud.json OVERWROTE the live interface/hud copy");
            }
            c.note("migrated " + rows.length + " files, 1 folder, 1 AP3 legacy child; foreign file untouched; live "
                    + "hud.json kept over a stale root copy (root copy " + (Files.exists(config.resolve(
                    "killer560smod-hud.json")) ? "left in place" : "removed") + ")");
        } finally {
            for (Path p : cleanup) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // wiped with the run dir anyway
                }
            }
        }
    }

    // ---- 340 main menu --------------------------------------------------------------------------------------

    /**
     * The themed main menu (mainmenu/*) on the title screen and, with Themed Menus on, the vanilla menus behind it,
     * in every on/off combination of its three settings. The theme turns itself off for the session and logs an
     * ERROR on any throw inside a mixin (mainmenu/MainMenuTheme.java:85), so "it rendered" is MainMenuTheme.failed
     * still false, plus no killer560smod ERROR line.
     */
    static void mainMenu(UiCase c) {
        Object cfg = Mod.cfg("mainmenu.MainMenuThemeConfig");
        boolean e0 = (Boolean) Mod.call(cfg, "isEnabled");
        boolean p0 = (Boolean) Mod.call(cfg, "isParticles");
        boolean o0 = (Boolean) Mod.call(cfg, "isOtherMenus");
        List<String> shown = new ArrayList<>();
        try {
            boolean[][] combos = {{true, true, true}, {true, false, false}, {false, true, true}};
            for (boolean[] combo : combos) {
                c.onClient(mc -> {
                    Mod.call(cfg, "setEnabled", combo[0]);
                    Mod.call(cfg, "setParticles", combo[1]);
                    Mod.call(cfg, "setOtherMenus", combo[2]);
                    return null;
                });
                List<Supplier<Screen>> screens = List.of(
                        TitleScreen::new,
                        () -> new net.minecraft.client.gui.screens.options.OptionsScreen(new TitleScreen(),
                                Minecraft.getInstance().options, false),
                        () -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen()),
                        () -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen()));
                for (Supplier<Screen> make : screens) {
                    String label = c.onClient(mc -> {
                        Screen s = make.get();
                        McCompat.setScreen(mc, s);
                        return s.getClass().getSimpleName();
                    });
                    c.ticks(4);
                    Frames.Drawn d = c.onClient(mc -> Frames.extractFrames(mc, McCompat.screen(mc), 3));
                    shown.add(label + (combo[0] ? "" : "(theme off)") + " " + d.total());
                    if (d.total() == 0) {
                        c.problem(label + " drew nothing");
                    }
                }
                if ((Boolean) Mod.field("mainmenu.MainMenuTheme", "failed")) {
                    c.problem("MainMenuTheme turned itself off after an error (enabled=" + combo[0] + ", particles="
                            + combo[1] + ", otherMenus=" + combo[2] + "); see the ERROR line in the log slice");
                    break;
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", e0);
                Mod.call(cfg, "setParticles", p0);
                Mod.call(cfg, "setOtherMenus", o0);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
        }
        c.note("frames (screen, elements in its smallest frame): " + shown);
        c.check(shown.size() >= 8, "only " + shown.size() + " menu screens shown");
    }

    // ---- 350 termism ----------------------------------------------------------------------------------------

    /**
     * Termism's practice screen for every TerminalType: built, rendered, then every grid cell clicked once with a
     * real mouseClicked (the practice board is local: no packets). Its state (solved/remainingMatches) is read
     * before and after, so a click sweep that never landed is visible.
     */
    static void termism(UiCase c) {
        Class<?> screenCls = R.cls("termism.TermismPracticeScreen");
        // TermismPracticeScreen(Screen parent, terminals.TerminalType type) - termism/TermismPracticeScreen.java
        Class<?> typeCls = R.cls("terminals.TerminalType");
        Object[] types = typeCls.getEnumConstants();
        c.check(types != null && types.length > 0, "no TerminalType constants found");
        List<String> rows = new ArrayList<>();
        for (Object type : types) {
            String row = c.onClient(mc -> {
                try {
                    Screen s = (Screen) screenCls.getConstructor(Screen.class, typeCls).newInstance(null, type);
                    McCompat.setScreen(mc, s);
                    Frames.extractFrames(mc, s, 3);
                    int ox = (Integer) R.get(s, "gridOriginX");
                    int oy = (Integer) R.get(s, "gridOriginY");
                    int cols = (Integer) R.get(s, "columns");
                    int rowsN = (Integer) R.get(s, "rows");
                    String before = R.get(s, "solved") + "/" + R.get(s, "remainingMatches");
                    int clicks = 0;
                    for (int y = 0; y < rowsN; y++) {
                        for (int x = 0; x < cols; x++) {
                            if (McCompat.screen(mc) != s) {
                                break;
                            }
                            s.mouseClicked(new MouseButtonEvent(ox + x * 18 + 9, oy + y * 18 + 9,
                                    new MouseButtonInfo(0, 0)), false);
                            clicks++;
                        }
                    }
                    Frames.extractFrames(mc, s, 1);
                    String after = R.get(s, "solved") + "/" + R.get(s, "remainingMatches");
                    return type + ": " + cols + "x" + rowsN + " grid, " + clicks + " clicks, solved/remaining "
                            + before + " -> " + after;
                } catch (Throwable t) {
                    c.problem(type + ": " + UiCase.describe(t));
                    return type + ": THREW";
                }
            });
            c.ticks(3);
            rows.add(row);
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.note(String.join("; ", rows));
    }

    // ---- 360 visuals ----------------------------------------------------------------------------------------

    /** Client visual features switched on in a singleplayer world for 60 rendered ticks. */
    static final String[] VISUALS = {
            "trail.TrailConfig", "realtime.RealTimeConfig", "lagdisplay.LagDisplayConfig", "position.PositionConfig",
            "trajectories.TrajectoriesConfig", "helditem.HeldItemConfig", "nofire.NoFireConfig",
            "diorite.DioriteGlassConfig", "armourdye.ArmourDyeConfig", "itemrarity.ItemRarityConfig",
            "enchantcolors.EnchantColorsConfig", "fullbright.FullbrightConfig", "motionblur.MotionBlurConfig",
            "inventoryhud.InventoryHudConfig", "playerstats.PlayerStatsConfig", "quiver.QuiverDisplayConfig",
            "etherwarpoverlay.EtherwarpOverlayConfig", "livemap.LiveMapConfig",
    };

    static void visuals(UiCase c) {
        Map<String, Boolean> before = new LinkedHashMap<>();
        List<String> drawnIds = new ArrayList<>();
        try {
            c.onClient(mc -> {
                for (String k : VISUALS) {
                    if (!Mod.has(k)) {
                        continue;
                    }
                    try {
                        before.put(k, (Boolean) Mod.get(k, "isEnabled"));
                        Mod.set(k, "setEnabled", true);
                    } catch (AssertionError e) {
                        c.problem(k + ": " + e.getMessage());
                    }
                }
                McCompat.setScreen(mc, null);
                return null;
            });
            long since = System.currentTimeMillis();
            c.ticks(60);
            Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name()));
            dev.testkit.harness.Report.screenshot(c.name(), shot);
            c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<Object> all = (List<Object>) Mod.staticCall("hud.HudElementRegistry", "all");
                for (Object e : all) {
                    String id = (String) Mod.call(e, "id");
                    long ms = (Long) Mod.staticCall("hud.HudSeen", "msSince", id, System.currentTimeMillis());
                    if (ms >= 0 && ms < System.currentTimeMillis() - since + 50) {
                        drawnIds.add(id);
                    }
                }
                return null;
            });
        } finally {
            c.onClient(mc -> {
                before.forEach((k, v) -> Mod.set(k, "setEnabled", v));
                return null;
            });
        }
        c.note("switched on " + before.size() + " visual features for 60 ticks; HUD elements that drew in that "
                + "window: " + drawnIds.size() + " " + drawnIds);
        c.check(before.size() >= 15, "only " + before.size() + " visual features could be switched on");
        c.check(!drawnIds.isEmpty(), "no HUD element drew while every visual was on - the render path never ran");
    }

    // ---- 365 overlay draws ----------------------------------------------------------------------------------

    /**
     * The mod's popup text ({@code notify.ModOverlayMessage}) really reaches the screen. It is drawn by a Fabric HUD
     * layer since 2026-10-04 (seven {@code Gui} mixins crashed 26.2 at startup), and "the jar booted" says nothing
     * about whether a layer draws - so this counts theme-orange pixels (0xCC6600) in the middle of a screenshot,
     * where the popup is centred.
     */
    static void overlayDraws(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Mod.staticCall("notify.ModOverlayMessage", "show", "TESTKIT OVERLAY CHECK WWWWWWWWWWWW", 10_000L);
            return null;
        });
        c.ticks(10);
        Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name()));
        dev.testkit.harness.Report.screenshot(c.name(), shot);
        int orange = 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            int w = img.getWidth(), h = img.getHeight();
            for (int y = h * 2 / 5; y < h * 3 / 5; y++) {
                for (int x = w / 5; x < w * 4 / 5; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if (Math.abs(r - 0xCC) <= 12 && Math.abs(g - 0x66) <= 12 && b <= 12) {
                        orange++;
                    }
                }
            }
        } catch (java.io.IOException e) {
            c.problem("could not read the screenshot " + shot + ": " + e);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("notify.ModOverlayMessage", "show", "", 1L);
                return null;
            });
        }
        c.note(orange + " theme-orange pixel(s) in the centre of the screen with a popup showing");
        c.check(orange >= 40, "the popup text never drew - its HUD layer did not run");
    }

    // ---- 370 deny -------------------------------------------------------------------------------------------

    /**
     * What the whole UI run did to the machine outside the JVM. The child-process watcher (ProcWatch) saw every
     * process this client started - clipboard writes and opens go through PowerShell/explorer/a browser - and there
     * must be none. ExternalOpen's "[TestHook] would open" lines are near misses the hook caught; they are listed.
     */
    static void deny(UiCase c, ProcWatch watch, long suiteLogMark) {
        c.check("true".equals(System.getProperty("killer560.test.noExternalOpen")),
                "killer560.test.noExternalOpen is not set - OS opens would be real");
        c.check("true".equals(System.getProperty("killer560.net.offline")), "killer560.net.offline is not set");
        String accounts = System.getProperty("prismaccountswitcher.accountsFile", "");
        c.check(accounts.contains("testkit-fixtures"), "prismaccountswitcher.accountsFile is not the empty fixture: "
                + accounts);
        List<String> procs = watch.seen();
        c.note("child processes started during the UI run: " + procs.size() + (procs.isEmpty() ? "" : " " + procs));
        if (!procs.isEmpty()) {
            c.problem("the client started " + procs.size() + " process(es) during the UI run (clipboard/OS side "
                    + "effects go through child processes): " + procs);
        }
        List<String> opens = new ArrayList<>();
        for (String line : LogTap.since(suiteLogMark)) {
            if (line.contains("[TestHook] would open")) {
                opens.add(line);
            }
        }
        c.note("ExternalOpen near misses (caught by noExternalOpen): " + opens.size()
                + (opens.isEmpty() ? "" : " " + opens));
        c.check(watch.polls() > 10, "the process watcher only polled " + watch.polls() + " times - it was not running");
    }
}
