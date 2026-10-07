package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mod branch cheat-tabs (killer560, 2026-10-07).
 *
 * <p>391 "auto close chest should be in a cheat only version. also make auto blood camp its own cheat tab": the tab
 * tree, the rows each tab builds and their tooltips, on either jar; in the legit jar, that Auto Close Chest's closing
 * code is not in the class files at all, and that with its saved setting forced ON a chest-titled menu packet still
 * opens its screen (the cheat jar closes it, the positive control).
 *
 * <p>392 Blood Camp, "make the kill timer ... make a sound once it starts counting down and as you need to kill" and
 * "nearly identical to noamm's in look and function": real armour stands wearing a blood-mob skull, moved on the
 * integrated server beside a zombie wearing a Watcher skull, so the mod tracks them from real move packets. Asserts the
 * drawn geometry against NoammAddons' numbers, the line's start against the stand's position, the predicted spot
 * against where the stand stops, and the two sounds (once each, merged across mobs, silent when switched off).
 */
final class CheatTabsCases {

    private static final String BC_CFG = "bloodcamp.BloodCampConfig";
    private static final String BC = "bloodcamp.BloodCampFeature";
    private static final String ACC_CFG = "autoclosechest.AutoCloseChestConfig";
    private static final String TIPS = "gui.SettingTooltips";
    private static final List<String> AUTO_ROWS = List.of("Trigger Bot", "Aura", "Auto Detect Lag", "Click Offset");
    private static final List<String> LEGIT_ROWS = List.of("Show Overlay", "Kill Popup", "Spawn Line",
            "Countdown Start Sound", "Kill Sound", "Start Sound Type", "Kill Sound Type", "Sound Volume");

    private CheatTabsCases() {
    }

    // ---- 391 ------------------------------------------------------------------------------------------------

    static void cheatTabs(UiCase c) throws Exception {
        boolean cheat = Mod.isCheat();
        Map<String, Object> tree = c.onClient(mc -> {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(d.tree(), flat);
                out.put("flat", flat);
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            }
            return out;
        });
        c.check(!tree.containsKey("error"), "could not open gui.ModScreen: " + tree.get("error"));
        @SuppressWarnings("unchecked")
        List<ModScreenDriver.Node> flat = (List<ModScreenDriver.Node>) tree.get("flat");
        List<ModScreenDriver.Node> abc = named(flat, "Auto Blood Camp");
        List<ModScreenDriver.Node> acc = named(flat, "Auto Close Chest");
        List<ModScreenDriver.Node> bc = named(flat, "Blood Camp");
        c.note("tabs named Auto Blood Camp " + paths(abc) + ", Auto Close Chest " + paths(acc) + ", Blood Camp "
                + paths(bc) + " (" + (cheat ? "cheat" : "legit") + " jar)");
        c.check(bc.size() == 1, "expected exactly one Blood Camp tab, found " + paths(bc));

        Object bcCfg = c.onClient(mc -> Mod.cfg(BC_CFG));
        boolean[] saved = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(bcCfg, "isEnabledRaw"),
                (Boolean) Mod.call(bcCfg, "getTriggerBotRaw"), (Boolean) Mod.call(bcCfg, "getAuraRaw")});
        try {
            // Every row visible: Blood Camp, Trigger Bot and Aura ON (in memory only; restored below).
            c.onClient(mc -> {
                Mod.call(bcCfg, "setEnabled", true);
                Mod.call(bcCfg, "setTriggerBotEnabled", true);
                Mod.call(bcCfg, "setAuraEnabled", true);
                return null;
            });
            List<String> bcLabels = labels(c, bc.get(0));
            c.note("Blood Camp builds " + bcLabels);
            for (String row : AUTO_ROWS) {
                if (hasRow(bcLabels, row)) {
                    c.problem("Blood Camp still shows the automation row '" + row + "'");
                }
            }
            if (bcLabels.stream().anyMatch(l -> l.contains("Cheat Build"))) {
                c.problem("Blood Camp still shows a Cheat Build section header");
            }
            for (String row : LEGIT_ROWS) {
                if (!hasRow(bcLabels, row)) {
                    c.problem("Blood Camp lost its legit row '" + row + "'");
                }
                tooltip(c, "Blood Camp", row, "blood camp/");
            }

            if (cheat) {
                c.check(abc.size() == 1, "cheat jar: expected one Auto Blood Camp tab, found " + paths(abc));
                c.check(acc.size() == 1, "cheat jar: expected one Auto Close Chest tab, found " + paths(acc));
                c.check(isCheatOnly(c, abc.get(0)), "Auto Blood Camp is not marked cheat-only (red title)");
                c.check(isCheatOnly(c, acc.get(0)), "Auto Close Chest is not marked cheat-only (red title)");
                List<String> abcLabels = labels(c, abc.get(0));
                c.note("Auto Blood Camp builds " + abcLabels);
                for (String row : AUTO_ROWS) {
                    if (!hasRow(abcLabels, row)) {
                        c.problem("Auto Blood Camp has no '" + row + "' row");
                    }
                    String tip = tooltip(c, "Auto Blood Camp", row, "auto blood camp/");
                    c.note("tooltip Auto Blood Camp/" + row + ": " + tip);
                }
                List<String> accLabels = labels(c, acc.get(0));
                c.note("Auto Close Chest builds " + accLabels);
                if (!hasRow(accLabels, "Auto Close Chest")) {
                    c.problem("Auto Close Chest builds no 'Auto Close Chest' row");
                }
                c.note("tooltip Auto Close Chest: " + tooltip(c, "Auto Close Chest", "Auto Close Chest",
                        "auto close chest/"));
            } else {
                if (!abc.isEmpty() || !acc.isEmpty()) {
                    c.problem("legit jar has tab(s) " + paths(abc) + " " + paths(acc));
                }
                // No leaf anywhere builds an Auto Close Chest or Blood Camp automation row.
                List<String> leaks = new ArrayList<>();
                for (ModScreenDriver.Node n : flat) {
                    if (n.folder()) {
                        continue;
                    }
                    for (String l : labels(c, n)) {
                        if (l.startsWith("Auto Close Chest")) {
                            leaks.add(n.path() + " / " + l);
                        }
                    }
                }
                if (!leaks.isEmpty()) {
                    c.problem("legit jar builds Auto Close Chest rows: " + leaks);
                }
                String tip = c.onClient(mc -> (String) Mod.staticCall(TIPS, "describe", "Auto Close Chest",
                        "Auto Close Chest"));
                if (tip != null) {
                    c.problem("legit jar still carries an Auto Close Chest tooltip: " + tip);
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(bcCfg, "setEnabled", saved[0]);
                Mod.call(bcCfg, "setTriggerBotEnabled", saved[1]);
                Mod.call(bcCfg, "setAuraEnabled", saved[2]);
                return null;
            });
        }

        // The class files: the close packet only exists in the cheat jar's feature, and the legit mixin never calls it.
        String feature = classText("com/killer560/hub/autoclosechest/AutoCloseChestFeature.class");
        String mixin = classText("com/killer560/hub/autoclosechest/mixin/AutoCloseChestMixin.class");
        boolean closeInFeature = feature.contains("ServerboundContainerClosePacket");
        boolean mixinCalls = mixin.contains("shouldAutoClose");
        c.note("AutoCloseChestFeature.class references ServerboundContainerClosePacket: " + closeInFeature
                + "; AutoCloseChestMixin.class calls shouldAutoClose: " + mixinCalls);
        if (cheat != closeInFeature) {
            c.problem((cheat ? "cheat" : "legit") + " jar: AutoCloseChestFeature "
                    + (closeInFeature ? "still sends" : "does not send") + " the close packet");
        }
        if (cheat != mixinCalls) {
            c.problem((cheat ? "cheat" : "legit") + " jar: the openScreen mixin "
                    + (mixinCalls ? "still calls" : "does not call") + " shouldAutoClose");
        }

        // Runtime: saved setting ON, in a dungeon, a "Chest" menu packet. Legit: the screen opens. Cheat: it does not.
        Object accCfg = c.onClient(mc -> Mod.cfg(ACC_CFG));
        boolean accSaved = c.onClient(mc -> (Boolean) Mod.call(accCfg, "isEnabledRaw"));
        try {
            c.onClient(mc -> {
                Mod.call(accCfg, "setEnabled", true);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                return null;
            });
            boolean gated = c.onClient(mc -> (Boolean) Mod.call(accCfg, "isEnabled"));
            c.onClient(mc -> {
                mc.getConnection().handleOpenScreen(new ClientboundOpenScreenPacket(77, MenuType.GENERIC_9x3,
                        Component.literal("Chest")));
                return null;
            });
            c.ticks(2);
            String screen = c.onClient(mc -> {
                var s = McCompat.screen(mc);
                return s == null ? "none" : s.getClass().getSimpleName()
                        + (s instanceof AbstractContainerScreen<?> ? " (container)" : "");
            });
            boolean opened = c.onClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>);
            c.note("saved Auto Close Chest ON, isEnabled() = " + gated + "; 'Chest' 9x3 packet -> screen " + screen);
            if (cheat) {
                c.check(gated, "cheat jar: isEnabled() false with the setting ON (Skyblock gate is off here)");
                c.check(!opened, "cheat jar: the secret chest screen opened - Auto Close Chest did not close it");
            } else {
                c.check(!gated, "legit jar: isEnabled() is true with a saved ON - the gate is missing");
                c.check(opened, "legit jar: the chest screen did not open - something closed it");
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(accCfg, "setEnabled", accSaved);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
        }
    }

    private static List<ModScreenDriver.Node> named(List<ModScreenDriver.Node> flat, String name) {
        List<ModScreenDriver.Node> out = new ArrayList<>();
        for (ModScreenDriver.Node n : flat) {
            if (name.equals(n.name())) {
                out.add(n);
            }
        }
        return out;
    }

    private static List<String> paths(List<ModScreenDriver.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (ModScreenDriver.Node n : nodes) {
            out.add(n.path());
        }
        return out;
    }

    private static boolean isCheatOnly(UiCase c, ModScreenDriver.Node n) {
        return c.onClient(mc -> (Boolean) Mod.call(n.tab(), "isCheatOnly"));
    }

    private static List<String> labels(UiCase c, ModScreenDriver.Node n) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            try {
                Method build = TabSweep.buildMethod();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) build.invoke(n.tab(), 0, 0, 300, (Runnable) () -> { });
                for (AbstractWidget w : ws) {
                    out.add(ModScreenDriver.label(w));
                }
            } catch (ReflectiveOperationException e) {
                out.add("THREW " + e.getCause());
            }
            return out;
        });
    }

    private static boolean hasRow(List<String> labels, String row) {
        for (String l : labels) {
            if (l.equals(row) || l.startsWith(row + ":")) {
                return true;
            }
        }
        return false;
    }

    /** The tooltip a row in {@code tab} gets, which must be non-empty and come from the tab-scoped key. */
    private static String tooltip(UiCase c, String tab, String row, String scope) {
        String tip = c.onClient(mc -> (String) Mod.staticCall(TIPS, "describe", tab, row));
        String key = c.onClient(mc -> (String) Mod.staticCall(TIPS, "key", row));
        @SuppressWarnings("unchecked")
        Map<String, String> all = (Map<String, String>) Mod.field(Mod.ROOT + TIPS, "DESCRIPTIONS");
        String scoped = all.get(scope + key);
        if (tip == null || tip.isBlank()) {
            c.problem("no tooltip for '" + tab + "' row '" + row + "'");
        } else if (scoped == null) {
            c.problem("'" + tab + "' row '" + row + "' has no '" + scope + key + "' entry (falls back to: " + tip + ")");
        } else if (!scoped.equals(tip)) {
            c.problem("'" + tab + "' row '" + row + "' resolves to '" + tip + "', not its scoped entry");
        }
        return tip;
    }

    /** The class file from the loaded mod jar, as Latin-1 text (constant-pool names survive intact). */
    private static String classText(String path) throws Exception {
        var mod = FabricLoader.getInstance().getModContainer("killer560smod").orElseThrow();
        for (Path root : mod.getRootPaths()) {
            Path p = root.resolve(path);
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            }
        }
        throw new AssertionError("[391] " + path + " is not in the mod jar");
    }

    // ---- 392 ------------------------------------------------------------------------------------------------

    /** NoammAddons' Blood Camp helper, read from its source (BloodCamp.kt, Render3D.kt; 2026-10-07). */
    private static final int NOAMM_BOX = 0xFF00FF;
    private static final int NOAMM_BOX_KILL = 0x00FF00;
    private static final int NOAMM_LINE = 0x00FFFF;
    private static final float NOAMM_BOX_WIDTH = 2.5f;
    private static final float NOAMM_LINE_WIDTH = 2f;

    static void bloodCamp(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(BC_CFG));
        Object[] saved = c.onClient(mc -> new Object[]{Mod.call(cfg, "isEnabledRaw"), Mod.call(cfg, "getShowOverlayRaw"),
                Mod.call(cfg, "getSpawnLineRaw"), Mod.call(cfg, "isCountdownStartSound"), Mod.call(cfg, "isKillSound"),
                Mod.call(cfg, "getSoundVolume"), Mod.call(cfg, "getTriggerBotRaw"), Mod.call(cfg, "getAuraRaw")});
        Difficulty[] oldDifficulty = new Difficulty[1];
        try {
            // Reference numbers, ours against Noamm's.
            int box = ((Number) Mod.field(Mod.ROOT + BC, "BOX_COLOR")).intValue() & 0xFFFFFF;
            int boxKill = ((Number) Mod.field(Mod.ROOT + BC, "BOX_COLOR_KILLABLE")).intValue() & 0xFFFFFF;
            int line = ((Number) Mod.field(Mod.ROOT + BC, "LINE_COLOR")).intValue() & 0xFFFFFF;
            float boxW = ((Number) Mod.field(Mod.ROOT + BC, "BOX_LINE_WIDTH")).floatValue();
            float lineW = c.onClient(mc -> ((Number) Mod.call(cfg, "getSpawnLineWidth")).floatValue());
            float textScale = c.onClient(mc -> ((Number) Mod.call(cfg, "getTimerTextScale")).floatValue());
            c.note(String.format(Locale.ROOT, "ours vs Noamm: box #%06X vs #%06X, due #%06X vs #%06X, line #%06X vs"
                            + " #%06X, box width %.1f vs %.1f, line width %.1f vs %.1f, text %.3f vs %.3f per px",
                    box, NOAMM_BOX, boxKill, NOAMM_BOX_KILL, line, NOAMM_LINE, boxW, NOAMM_BOX_WIDTH, lineW,
                    NOAMM_LINE_WIDTH, textScale * 0.025f, 2 * 0.025f));
            if (box != NOAMM_BOX || boxKill != NOAMM_BOX_KILL || line != NOAMM_LINE || boxW != NOAMM_BOX_WIDTH
                    || lineW != NOAMM_LINE_WIDTH || Math.abs(textScale - 2f) > 1e-4) {
                c.problem("Blood Camp's look differs from NoammAddons' (numbers above)");
            }

            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowOverlay", true);
                Mod.call(cfg, "setSpawnLine", true);
                Mod.call(cfg, "setCountdownStartSound", true);
                Mod.call(cfg, "setKillSound", true);
                Mod.call(cfg, "setSoundVolume", 0.5f);
                Mod.call(cfg, "setTriggerBotEnabled", false);
                Mod.call(cfg, "setAuraEnabled", false);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                var server = mc.getSingleplayerServer();
                oldDifficulty[0] = server.getWorldData().getDifficulty();
                return null;
            });
            server(c, s -> s.setDifficulty(Difficulty.EASY, true));
            command(c, "tp @a 0.5 -60 0.5 0 15");
            String watcherTex = anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES"));
            String mobTex = anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES"));
            command(c, "summon zombie 0.5 -60 10.5 {Tags:[\"bctest\"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,"
                    + "PersistenceRequired:1b,equipment:{head:" + head(watcherTex) + "}}");
            c.ctx().waitFor(mc -> Mod.field(Mod.ROOT + BC, "watcherEntityId") != null, 200);
            c.note("Watcher found by the mod: id " + Mod.field(Mod.ROOT + BC, "watcherEntityId"));

            // ---- A: one mob, geometry + both sounds at their moments ----
            int[] before = sounds(c);
            Trip a = trip(c, mobTex, List.of(new Vec3(-7.5, -60, 14.5)), "bcA");
            int[] afterA = sounds(c);
            c.note(String.format(Locale.ROOT, "A: first wave %s, D %.1f, %d ticks moving, start sound at tick +%d,"
                            + " kill sound at tick +%d (countdown %.1f ticks, box green at +%d); sounds %d start, %d kill",
                    a.firstSpawn, a.distance, a.moveTicks, a.startSoundAt, a.killSoundAt, a.countdown, a.greenAt,
                    afterA[0] - before[0], afterA[1] - before[1]));
            c.note(String.format(Locale.ROOT, "A: predicted spot error %.3f blocks (new model) vs %.3f (old model,"
                            + " start one packet out of the wall); line start to stand+2: %.3f settled, %.3f worst in"
                            + " flight (one step is %.3f); text '%s'", a.predictError, a.oldPredictError,
                    a.lineSettledError, a.lineFlightError, a.step, a.text));
            c.check(a.tracked, "A: the mod never tracked the stand - no move packets reached Blood Camp");
            if (afterA[0] - before[0] != 1) {
                c.problem("A: expected exactly one countdown start sound, got " + (afterA[0] - before[0]));
            }
            if (afterA[1] - before[1] != 1) {
                c.problem("A: expected exactly one kill sound, got " + (afterA[1] - before[1]));
            }
            if (a.startSoundAt < 0 || a.startSoundAt > 8) {
                c.problem("A: the start sound came at tick +" + a.startSoundAt + ", not with the first moves");
            }
            if (a.killSoundAt < 0 || Math.abs(a.killSoundAt - a.greenAt) > 1) {
                c.problem("A: the kill sound came at +" + a.killSoundAt + ", the box turned green at +" + a.greenAt);
            }
            c.note("A: box green " + a.greenAfterStart + " ticks after the first move packet (countdown "
                    + a.countdown + ", minus the ping lead)");
            if (a.greenAfterStart < a.countdown - 2 || a.greenAfterStart > a.countdown + 2) {
                c.problem("A: the box turned green " + a.greenAfterStart + " ticks after the countdown started, not ~"
                        + a.countdown);
            }
            if (a.predictError > 0.05) {
                c.problem(String.format(Locale.ROOT, "A: predicted spot %.3f blocks from where the stand stopped",
                        a.predictError));
            }
            if (a.lineSettledError > 0.01) {
                c.problem(String.format(Locale.ROOT, "A: the line starts %.3f blocks from the stand's head (+2)",
                        a.lineSettledError));
            }
            if (a.lineFlightError > a.step + 0.05) {
                c.problem(String.format(Locale.ROOT, "A: in flight the line start was %.3f from the stand (+2)",
                        a.lineFlightError));
            }
            if (a.boxError > 1e-6) {
                c.problem("A: the box is not Noamm's 1x1x1 from +1.5 to +2.5 over the spot: " + a.boxDesc);
            }

            // ---- B: three mobs leaving together -> one start sound, one kill sound ----
            before = sounds(c);
            Trip b = trip(c, mobTex, List.of(new Vec3(-7.5, -60, 13.5), new Vec3(-7.5, -60, 15.5),
                    new Vec3(-7.5, -60, 16.5)), "bcB");
            int[] afterB = sounds(c);
            c.note("B: three mobs together -> " + (afterB[0] - before[0]) + " start, " + (afterB[1] - before[1])
                    + " kill sound(s)");
            c.check(b.tracked, "B: the mod never tracked the stands");
            if (afterB[0] - before[0] != 1 || afterB[1] - before[1] != 1) {
                c.problem("B: three mobs at one moment must make one start and one kill sound");
            }

            // ---- C: both sounds off -> silence ----
            c.onClient(mc -> {
                Mod.call(cfg, "setCountdownStartSound", false);
                Mod.call(cfg, "setKillSound", false);
                return null;
            });
            before = sounds(c);
            Trip cc = trip(c, mobTex, List.of(new Vec3(-7.5, -60, 14.5)), "bcC");
            int[] afterC = sounds(c);
            c.note("C: sounds OFF -> " + (afterC[0] - before[0]) + " start, " + (afterC[1] - before[1]) + " kill");
            c.check(cc.tracked, "C: the mod never tracked the stand");
            if (afterC[0] != before[0] || afterC[1] != before[1]) {
                c.problem("C: a sound played with both sound toggles OFF");
            }
            if (a.shot != null) {
                c.note("A: screenshot in flight " + a.shot);
            }
        } finally {
            command(c, "kill @e[tag=bctest]");
            command(c, "kill @e[tag=bcA]");
            command(c, "kill @e[tag=bcB]");
            command(c, "kill @e[tag=bcC]");
            if (oldDifficulty[0] != null) {
                Difficulty d = oldDifficulty[0];
                server(c, s -> s.setDifficulty(d, true));
            }
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", saved[0]);
                Mod.call(cfg, "setShowOverlay", saved[1]);
                Mod.call(cfg, "setSpawnLine", saved[2]);
                Mod.call(cfg, "setCountdownStartSound", saved[3]);
                Mod.call(cfg, "setKillSound", saved[4]);
                Mod.call(cfg, "setSoundVolume", saved[5]);
                Mod.call(cfg, "setTriggerBotEnabled", saved[6]);
                Mod.call(cfg, "setAuraEnabled", saved[7]);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
        }
    }

    /** What one launch measured. */
    private static final class Trip {
        boolean tracked;
        boolean firstSpawn;
        double distance;
        double step;
        int moveTicks;
        double countdown;
        int startSoundAt = -1;
        int killSoundAt = -1;
        int greenAt = -1;
        /** Game ticks from the countdown's own start (the first move packet) to the box turning green. */
        long greenAfterStart = -1;
        double predictError = Double.NaN;
        double oldPredictError = Double.NaN;
        double lineSettledError = Double.NaN;
        double lineFlightError;
        double boxError = Double.NaN;
        String boxDesc = "";
        String text = "";
        Path shot;
    }

    /**
     * Summon a stand per start (tag {@code tag}), move them all +x on the server one step a tick so they arrive when
     * the countdown runs out, then hold still until the box is green and the kill moment passed. Measures stand 0.
     */
    private static Trip trip(UiCase c, String mobTex, List<Vec3> starts, String tag) {
        Trip t = new Trip();
        for (Vec3 s : starts) {
            command(c, String.format(Locale.ROOT, "summon armor_stand %.3f %.3f %.3f {Tags:[\"%s\"],NoGravity:1b,"
                    + "equipment:{head:%s}}", s.x, s.y, s.z, tag, head(mobTex)));
        }
        c.ticks(10);
        boolean first;
        try {
            first = (Boolean) c.onClient(mc -> Mod.staticCall("bloodcamp.BloodCampMoveTimer", "isFirstSpawns"));
        } catch (RuntimeException | AssertionError e) {
            first = true;
        }
        t.firstSpawn = first;
        t.distance = first ? 16.1 : 11.9;
        t.countdown = (first ? 40 : 0) + 38 + 0.8;
        int ticks = (int) Math.round(t.countdown) - 4;
        t.moveTicks = ticks;
        t.step = t.distance / ticks;
        Vec3 dir = new Vec3(1, 0, 0);
        List<Integer> ids = serverIds(c, tag);
        c.check(ids.size() == starts.size(), "[" + tag + "] summoned " + ids.size() + " of " + starts.size()
                + " stands");
        int standId = ids.get(0);
        int[] base = sounds(c);
        int startTick = -1;
        for (int i = 1; i <= ticks + 60; i++) {
            if (i <= ticks) {
                double f = i == ticks ? t.distance : t.step * i;
                List<Vec3> targets = new ArrayList<>();
                for (Vec3 s : starts) {
                    targets.add(s.add(dir.scale(f)));
                }
                move(c, tag, targets);
            }
            c.ticks(1);
            Object[] now = c.onClient(mc -> snapshot(mc, standId));
            if (now[0] != null && startTick < 0) {
                startTick = i;
            }
            int[] snd = sounds(c);
            if (t.startSoundAt < 0 && snd[0] > base[0]) {
                t.startSoundAt = i;
            }
            if (t.killSoundAt < 0 && snd[1] > base[1]) {
                t.killSoundAt = i;
            }
            double[] row = (double[]) now[1];
            if (row != null) {
                int color = (int) row[12] & 0xFFFFFF;
                if (t.greenAt < 0 && color == NOAMM_BOX_KILL) {
                    t.greenAt = i;
                    if (now[4] != null) {
                        t.greenAfterStart = (Long) now[5] - (Long) now[4];
                    }
                }
                Vec3 standPos = (Vec3) now[2];
                Vec3 lineStart = new Vec3(row[0], row[1], row[2]);
                if (standPos != null && i <= ticks) {
                    t.lineFlightError = Math.max(t.lineFlightError, lineStart.distanceTo(standPos.add(0, 2, 0)));
                }
                if (i == ticks / 2 && t.shot == null) {
                    try {
                        t.shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-flight"));
                    } catch (Throwable ignored) {
                        // a picture is a bonus, not the measurement
                    }
                }
            }
            if (i > ticks + 10 && t.killSoundAt >= 0 && t.greenAt >= 0) {
                break;
            }
        }
        t.tracked = startTick >= 0;
        if (t.startSoundAt >= 0 && startTick >= 0) {
            t.startSoundAt -= startTick;
            t.killSoundAt = t.killSoundAt < 0 ? -1 : t.killSoundAt - startTick;
            t.greenAt = t.greenAt < 0 ? -1 : t.greenAt - startTick;
        }
        Object[] last = c.onClient(mc -> snapshot(mc, standId));
        Vec3 end = starts.get(0).add(dir.scale(t.distance));
        if (last[0] != null) {
            Vec3 predicted = (Vec3) last[0];
            Vec3 oldStart = (Vec3) last[3];
            t.predictError = predicted.distanceTo(end);
            if (oldStart != null) {
                t.oldPredictError = oldStart.add(dir.scale(t.distance)).distanceTo(end);
            }
        }
        double[] row = (double[]) last[1];
        Vec3 standPos = (Vec3) last[2];
        if (row != null && standPos != null) {
            t.lineSettledError = new Vec3(row[0], row[1], row[2]).distanceTo(standPos.add(0, 2, 0));
            Vec3 p = (Vec3) last[0];
            t.boxError = Math.abs(row[6] - (p.x - 0.5)) + Math.abs(row[7] - (p.y + 1.5)) + Math.abs(row[8] - (p.z - 0.5))
                    + Math.abs(row[9] - (p.x + 0.5)) + Math.abs(row[10] - (p.y + 2.5)) + Math.abs(row[11] - (p.z + 0.5));
            t.boxDesc = String.format(Locale.ROOT, "[%.2f %.2f %.2f .. %.2f %.2f %.2f] over %.2f %.2f %.2f", row[6],
                    row[7], row[8], row[9], row[10], row[11], p.x, p.y, p.z);
        }
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) c.onClient(mc -> Mod.staticCall(BC, "lastFrameText"));
        t.text = texts.isEmpty() ? "" : texts.get(0);
        command(c, "kill @e[tag=" + tag + "]");
        c.ticks(5);
        return t;
    }

    /** [predicted end (Vec3) or null, last drawn row for the stand or null, stand client position, first exact]. */
    private static Object[] snapshot(Minecraft mc, int standId) {
        Entity e = mc.level.getEntity(standId);
        Object[] out = new Object[6];
        out[5] = mc.level.getGameTime();
        out[2] = e == null ? null : e.position();
        @SuppressWarnings("unchecked")
        Map<Object, Object> mobs = (Map<Object, Object>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        for (Map.Entry<Object, Object> m : mobs.entrySet()) {
            if (((Entity) m.getKey()).getId() == standId) {
                out[0] = Mod.field(m.getValue(), "endVector");
                out[3] = Mod.field(m.getValue(), "firstPacketExact");
                out[4] = Mod.field(m.getValue(), "startedAtTick");
            }
        }
        @SuppressWarnings("unchecked")
        List<double[]> frame = (List<double[]>) Mod.staticCall(BC, "lastFrame");
        for (double[] r : frame) {
            if ((int) r[14] == standId) {
                out[1] = r;
            }
        }
        return out;
    }

    private static int[] sounds(UiCase c) {
        return c.onClient(mc -> new int[]{(Integer) Mod.staticCall(BC, "countdownStartSoundsPlayed"),
                (Integer) Mod.staticCall(BC, "killSoundsPlayed")});
    }

    private static String head(String texture) {
        return "{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{properties:[{name:\"textures\","
                + "value:\"" + texture + "\"}]}}}";
    }

    private static String anyOf(Object set) {
        return (String) ((Collection<?>) set).iterator().next();
    }

    private static List<Integer> serverIds(UiCase c, String tag) {
        java.util.concurrent.atomic.AtomicReference<List<Integer>> out = new java.util.concurrent.atomic.AtomicReference<>();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                List<Integer> ids = new ArrayList<>();
                for (ArmorStand s : server.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains(tag))) {
                    ids.add(s.getId());
                }
                ids.sort(null);
                out.set(ids);
            });
            return null;
        });
        c.ctx().waitFor(mc -> out.get() != null, 100);
        return out.get();
    }

    /** Server-side setPos on each tagged stand (in id order), so the tracker sends ordinary move packets. */
    private static void move(UiCase c, String tag, List<Vec3> targets) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                List<ArmorStand> stands = new ArrayList<>(server.overworld().getEntities(
                        EntityTypeTest.forClass(ArmorStand.class), e -> e.entityTags().contains(tag)));
                stands.sort(java.util.Comparator.comparingInt(Entity::getId));
                for (int i = 0; i < stands.size() && i < targets.size(); i++) {
                    Vec3 p = targets.get(i);
                    stands.get(i).setPos(p.x, p.y, p.z);
                }
            });
            return null;
        });
    }

    private static void command(UiCase c, String cmd) {
        server(c, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
    }

    private static void server(UiCase c, java.util.function.Consumer<net.minecraft.server.MinecraftServer> task) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    task.accept(server);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
    }
}
