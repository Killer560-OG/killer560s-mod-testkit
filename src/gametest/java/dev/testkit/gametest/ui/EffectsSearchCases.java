package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1102-ui-hide-effects-* and 1102-ui-search-queries (mod effects-search-1102, killer560 2026-11-02: "find if there is a
 * way to hide these effects as well" and "i keep searching for things we have and I cant find them").
 * <ul>
 *   <li>hide-effects: with six effects on the player, the inventory's effects panel (right of the inventory) is drawn
 *       with Hide Potion Effects OFF and not drawn with it ON, with the Inventory Theme off and on alike - by pixels
 *       against a frame with no effects, and by the mixin's own drawn/canceled counters.</li>
 *   <li>hide-effects-hud: with no screen, Hide Effect Icons (HUD) OFF draws the top-right effect icons and ON does not
 *       (pixels and the HUD layer's counters).</li>
 *   <li>hide-effects-config: defaults (panel hidden, icons shown); an old Inventory Theme file saying
 *       "hidePotionEffects": false does not bring the panel back; both switches survive a save and reload and
 *       ProfileManager.reloadAllConfigs re-reads the file; the Hide Potion Effects card sits in Items with both rows,
 *       each with a tooltip, its switch standing for both, and a press flips and saves.</li>
 *   <li>search-queries: each query of {@link #QUERIES} lists its intended card (an exact card name among the first
 *       three results); a keystroke after the first is matched from memory, each well under the bound.</li>
 * </ul>
 */
final class EffectsSearchCases {

    static final String[] CASES = {"1102-ui-hide-effects", "1102-ui-hide-effects-hud", "1102-ui-hide-effects-config",
            "1102-ui-search-queries", "1102-ui-search-commands", "1102-ui-search-readme"};

    private static final String CFG = "hideeffects.HideEffectsConfig";
    private static final String FEAT = "hideeffects.HideEffectsFeature";
    private static final String THEME = "inventorytheme.InventoryThemeConfig";

    private EffectsSearchCases() {
    }

    private static boolean present(UiCase c) {
        if (!Mod.has(CFG)) {
            c.skipCase("the jar has no Hide Potion Effects card (mod effects-search-1102)");
            return false;
        }
        return true;
    }

    private static Object cfg() {
        return Mod.cfg(CFG);
    }

    private static long counter(String field) {
        return ((Number) Mod.field(FEAT, field)).longValue();
    }

    private static Path path(String cls) {
        return (Path) Mod.field(cls, "CONFIG_PATH");
    }

    private static byte[] read(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path p, byte[] b) {
        try {
            if (b == null) {
                Files.deleteIfExists(p);
            } else {
                Files.createDirectories(p.getParent());
                Files.write(p, b);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void command(UiCase c, String cmd) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        for (int i = 0; i < 100 && !done.get(); i++) {
            c.ticks(1);
        }
    }

    private static final String[] EFFECTS = {"strength 7", "haste 0", "jump_boost 5", "regeneration 8", "night_vision 1",
            "absorption 0"};

    /** For the HUD case: no Night Vision (it brightens the whole world, so a frame without it differs everywhere) and
     *  no Absorption (it adds hearts). */
    private static final String[] HUD_EFFECTS = {"strength 7", "haste 0", "jump_boost 5", "regeneration 8"};

    private static void giveEffects(UiCase c) {
        giveEffects(c, EFFECTS, true);
    }

    /** {@code hideParticles} true also hides the HUD icon: vanilla's MobEffectInstance(effect, duration, amplifier, ambient,
     *  visible) takes showIcon from visible, so the HUD case gives them with particles (its first run drew no icons). */
    private static void giveEffects(UiCase c, String[] effects, boolean hideParticles) {
        for (String e : effects) {
            String[] p = e.split(" ");
            command(c, "effect give @a minecraft:" + p[0] + " 9999 " + p[1] + " " + hideParticles);
        }
        for (int i = 0; i < 100 && c.onClient(mc -> mc.player.getActiveEffects().size()) < effects.length; i++) {
            c.ticks(1);
        }
    }

    private static void clearEffects(UiCase c) {
        command(c, "effect clear @a");
        for (int i = 0; i < 100 && c.onClient(mc -> mc.player.getActiveEffects().size()) > 0; i++) {
            c.ticks(1);
        }
    }

    private static BufferedImage shot(UiCase c, String suffix) {
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(4);
        try {
            String name = c.name() + "-" + suffix;
            Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(name, taken);
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (Exception e) {
            throw new AssertionError(UiCase.describe(e), e);
        }
    }

    private static int changed(BufferedImage a, BufferedImage b, int[] box) {
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(Math.min(a.getHeight(), b.getHeight()) - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(Math.min(a.getWidth(), b.getWidth()) - 1, box[2]); x++) {
                if (MapHeadCases.changed(a.getRGB(x, y), b.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Opens the inventory and returns the pixel box right of it (where the effects panel goes), or null. */
    private static int[] openInventory(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new InventoryScreen(mc.player));
            return null;
        });
        c.ticks(5);
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (!(s instanceof AbstractContainerScreen<?>)) {
                return null;
            }
            double px = mc.getWindow().getWidth() / (double) s.width;
            int left = (Integer) R.get(s, "leftPos");
            int top = (Integer) R.get(s, "topPos");
            int w = (Integer) R.get(s, "imageWidth");
            int h = (Integer) R.get(s, "imageHeight");
            return new int[]{(int) Math.ceil((left + w + 1) * px), (int) (top * px), mc.getWindow().getWidth() - 1,
                    (int) ((top + h) * px)};
        });
    }

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    // ---- 1102-ui-hide-effects ---------------------------------------------------------------------------------------

    static void panel(UiCase c) {
        if (!present(c)) {
            return;
        }
        Path hp = path(CFG);
        Path tp = path(THEME);
        byte[] hideSaved = read(hp);
        byte[] themeSaved = read(tp);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        Object cfg = cfg();
        Object theme = Mod.cfg(THEME);
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            clearEffects(c);
            int[] box = openInventory(c);
            String screen = c.onClient(mc -> String.valueOf(McCompat.screen(mc) == null ? null
                    : McCompat.screen(mc).getClass().getSimpleName()));
            if (box == null) {
                c.problem("no inventory screen opened (" + screen + ")");
                return;
            }
            c.note("screen " + screen + "; effects panel area px " + box[0] + "," + box[1] + ".." + box[2] + "," + box[3]);
            BufferedImage none = shot(c, "no-effects");
            closeScreen(c);
            giveEffects(c);
            int effects = c.onClient(mc -> mc.player.getActiveEffects().size());
            c.check(effects >= EFFECTS.length, "the player has " + effects + " effect(s), want " + EFFECTS.length);
            for (boolean themeOn : new boolean[]{false, true}) {
                for (boolean hide : new boolean[]{false, true}) {
                    c.onClient(mc -> {
                        Mod.call(theme, "setEnabled", themeOn);
                        Mod.call(cfg, "setHidePotionEffects", hide);
                        return null;
                    });
                    long hid0 = counter("panelHiddenFrames");
                    long shown0 = counter("panelShownFrames");
                    openInventory(c);
                    String tag = "theme-" + (themeOn ? "on" : "off") + "-hide-" + (hide ? "on" : "off");
                    BufferedImage img = shot(c, tag);
                    long hid = counter("panelHiddenFrames") - hid0;
                    long shown = counter("panelShownFrames") - shown0;
                    closeScreen(c);
                    int diff = changed(none, img, box);
                    c.note(String.format(Locale.ROOT, "%s: %d px changed right of the inventory against no effects; panel"
                            + " frames canceled %d, drawn %d", tag, diff, hid, shown));
                    if (hide) {
                        c.check(diff < 40, tag + ": the effects panel still drew (" + diff + " px changed)");
                        c.check(hid > 0 && shown == 0, tag + ": the panel was not canceled (canceled " + hid + ", drawn " + shown + ")");
                    } else {
                        c.check(diff > 400, tag + ": the effects panel did not draw (" + diff + " px changed) - the hidden"
                                + " checks would be vacuous");
                        c.check(shown > 0 && hid == 0, tag + ": the panel was not drawn by vanilla (canceled " + hid
                                + ", drawn " + shown + ")");
                    }
                }
            }
        } finally {
            closeScreen(c);
            clearEffects(c);
            write(hp, hideSaved);
            write(tp, themeSaved);
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Mod.staticCall(THEME, "load");
                return null;
            });
            HudEditorCases.setWindow(c, window[0], window[1], gui);
        }
    }

    // ---- 1102-ui-hide-effects-hud -----------------------------------------------------------------------------------

    static void hud(UiCase c) {
        if (!present(c)) {
            return;
        }
        Path hp = path(CFG);
        byte[] hideSaved = read(hp);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        Object cfg = cfg();
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            closeScreen(c);
            clearEffects(c);
            BufferedImage none = shot(c, "no-effects");
            giveEffects(c, HUD_EFFECTS, false);
            int w = none.getWidth();
            // Vanilla's icons: 24 px squares from the top right, a row for beneficial and one for harmful effects.
            int[] box = {w - 2 * 25 * HUD_EFFECTS.length - 2, 0, w - 1, 2 * 60};
            int[] diffs = new int[2];
            long[][] frames = new long[2][2];
            for (int i = 0; i < 2; i++) {
                boolean hide = i == 1;
                c.onClient(mc -> {
                    Mod.call(cfg, "setHideEffectIcons", hide);
                    return null;
                });
                long h0 = counter("hudHiddenFrames");
                long s0 = counter("hudShownFrames");
                BufferedImage img = shot(c, "icons-hide-" + (hide ? "on" : "off"));
                frames[i][0] = counter("hudHiddenFrames") - h0;
                frames[i][1] = counter("hudShownFrames") - s0;
                diffs[i] = changed(none, img, box);
                c.note(String.format(Locale.ROOT, "Hide Effect Icons %s: %d px changed in the top right against no effects;"
                        + " HUD layer frames hidden %d, drawn %d", hide ? "ON" : "OFF", diffs[i], frames[i][0], frames[i][1]));
            }
            c.check(diffs[0] > 150, "with Hide Effect Icons OFF the effect icons did not draw (" + diffs[0] + " px) - the"
                    + " hidden check would be vacuous");
            c.check(frames[0][1] > 0 && frames[0][0] == 0, "OFF: the HUD layer did not hand the icons to vanilla");
            c.check(diffs[1] < 40, "with Hide Effect Icons ON the icons still drew (" + diffs[1] + " px)");
            c.check(frames[1][0] > 0 && frames[1][1] == 0, "ON: the HUD layer did not hide the icons");
        } finally {
            clearEffects(c);
            write(hp, hideSaved);
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
            HudEditorCases.setWindow(c, window[0], window[1], gui);
        }
    }

    // ---- 1102-ui-hide-effects-config --------------------------------------------------------------------------------

    static void config(UiCase c) {
        if (!present(c)) {
            return;
        }
        Path hp = path(CFG);
        Path tp = path(THEME);
        byte[] hideSaved = read(hp);
        byte[] themeSaved = read(tp);
        try {
            // Defaults: a new install hides the panel and keeps the HUD icons.
            write(hp, null);
            Object fresh = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return cfg();
            });
            c.check((Boolean) Mod.call(fresh, "isHidePotionEffectsRaw"), "a new install does not hide the effects panel");
            c.check(!(Boolean) Mod.call(fresh, "isHideEffectIconsRaw"), "a new install hides the HUD effect icons");

            // An Inventory Theme file from before 2026-10-18 saying the panel was shown does not bring it back.
            write(tp, "{\"enabled\":false,\"hidePotionEffects\":false,\"themeChoiceMigrated\":true}".getBytes(StandardCharsets.UTF_8));
            write(hp, null);
            boolean afterOld = c.onClient(mc -> {
                Mod.staticCall(THEME, "load");
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(cfg(), "isHidePotionEffectsRaw");
            });
            c.check(afterOld, "an old Inventory Theme file with hidePotionEffects false turned the panel back on");

            // Both switches survive a save and a reload, and a profile apply's reloadAllConfigs re-reads the file.
            c.onClient(mc -> {
                Object x = cfg();
                Mod.call(x, "setHidePotionEffects", false);
                Mod.call(x, "setHideEffectIcons", true);
                Mod.call(x, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object back = cfg();
            c.check(!(Boolean) Mod.call(back, "isHidePotionEffectsRaw") && (Boolean) Mod.call(back, "isHideEffectIconsRaw"),
                    "the switches did not survive a save and reload");
            String file = new String(read(hp), StandardCharsets.UTF_8);
            c.note("saved " + hp.getFileName() + ": " + file.replaceAll("\\s+", " "));
            c.check(hp.toString().replace('\\', '/').contains("/killer560/"), "the file is not under config/killer560: " + hp);
            write(hp, "{\"hidePotionEffects\":true,\"hideEffectIcons\":false}".getBytes(StandardCharsets.UTF_8));
            c.onClient(mc -> {
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return null;
            });
            Object reloaded = cfg();
            c.check((Boolean) Mod.call(reloaded, "isHidePotionEffectsRaw") && !(Boolean) Mod.call(reloaded, "isHideEffectIconsRaw"),
                    "ProfileManager.reloadAllConfigs did not re-read the Hide Potion Effects file");

            // The card: in Items, beside Inventory Theme, both rows with tooltips, its switch standing for both.
            String why = c.onClient(mc -> {
                try {
                    Object tab = MenuACases.leaf("HidePotionEffectsTab");
                    String cat = MenuACases.name(MenuACases.category(tab));
                    if (!"Items".equals(cat)) {
                        return "the card is in " + cat + ", not Items";
                    }
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> built = (List<AbstractWidget>) tab.getClass()
                            .getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class)
                            .invoke(tab, 10, 10, 330, (Runnable) () -> { });
                    List<String> labels = new ArrayList<>();
                    for (AbstractWidget w : built) {
                        labels.add(ModScreenDriver.label(w));
                    }
                    c.note("Hide Potion Effects rows: " + labels);
                    if (built.size() != 2 || !labels.get(0).startsWith("Hide Potion Effects")
                            || !labels.get(1).startsWith("Hide Effect Icons (HUD)")) {
                        return "the rows are not Hide Potion Effects, Hide Effect Icons (HUD): " + labels;
                    }
                    for (String l : labels) {
                        String cut = l.substring(0, l.indexOf(':')).trim();
                        String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Items", cut);
                        if (tip == null || tip.isBlank()) {
                            return "the row " + cut + " has no tooltip";
                        }
                    }
                    List<?> masters = (List<?>) Mod.call(tab, "masterToggles", built);
                    if (masters.size() != 2) {
                        return "the card's switch stands for " + masters.size() + " row(s), want 2";
                    }
                    Object x = cfg();
                    boolean was = (Boolean) Mod.call(x, "isHidePotionEffectsRaw");
                    ModScreenDriver.press(built.get(0));
                    Mod.staticCall(CFG, "load");
                    boolean now = (Boolean) Mod.call(cfg(), "isHidePotionEffectsRaw");
                    if (now == was) {
                        return "pressing Hide Potion Effects did not flip and save it (" + was + " -> " + now + ")";
                    }
                    return null;
                } catch (Throwable t) {
                    return UiCase.describe(t);
                }
            });
            if (why != null) {
                c.problem(why);
            }
        } finally {
            write(hp, hideSaved);
            write(tp, themeSaved);
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Mod.staticCall(THEME, "load");
                return null;
            });
        }
    }

    // ---- 1102-ui-search-queries -------------------------------------------------------------------------------------

    /** query, then "!" for an exact card name (must be in the first three results), then the tab ids any of which counts. */
    static final String[][] QUERIES = {
            {"potion", "", "HidePotionEffectsTab"},
            {"effects", "", "HidePotionEffectsTab"},
            {"hide effects", "", "HidePotionEffectsTab"},
            {"hide potion effects", "!", "HidePotionEffectsTab"},
            {"potion effects", "", "HidePotionEffectsTab"},
            {"star mob", "", "MobEspTab"},
            {"starred", "", "MobEspTab"},
            {"starred mobs", "", "MobEspTab"},
            {"wither esp", "", "MobEspTab"},
            {"mob esp", "", "MobEspTab"},
            {"esp", "", "MobEspTab"},
            {"mob highlight", "", "MobEspTab"},
            {"bat esp", "", "MobEspTab"},
            {"dungeon map", "!", "LiveMapTab"},
            {"map", "", "LiveMapTab"},
            {"secret waypoints", "!", "SecretWaypointsTab"},
            {"secret waypoint", "", "SecretWaypointsTab"},
            {"secret marker", "", "SecretWaypointsTab"},
            {"chat keybinds", "!", "CommandKeybindsTab"},
            {"chat commands", "", "CommandKeybindsTab"},
            {"chat hotkey", "", "CommandKeybindsTab"},
            {"zpew", "", "ZeroPingEtherwarpTab"},
            {"etherwarp overlay", "!", "EtherwarpOverlayTab"},
            {"ew overlay", "", "EtherwarpOverlayTab"},
            {"terminal solver", "!", "TerminalSolverTab"},
            {"terms", "", "TerminalSolverTab", "TerminalTimersTab", "AutoTerminalTab"},
            {"simon says", "!", "SimonSaysTab"},
            {"blood camp", "!", "BloodCampTab"},
            {"hide players", "!", "HidePlayersTab"},
            {"player hider", "", "HidePlayersTab"},
            {"scoreboard", "", "CustomScoreboardTab"},
            {"fullbright", "", "DisplayOptionsTab"},
            {"brightness", "", "DisplayOptionsTab"},
            {"night vision", "", "DisplayOptionsTab"},
            {"fps", "", "LagDisplayTab"},
            {"performance hud", "!", "LagDisplayTab"},
            {"chest sounds", "", "SecretTab", "SecretClickedTab"},
            {"mute chest", "", "SecretTab", "SecretClickedTab"},
            {"click sounds", "!", "ClickSoundsTab"},
            {"auction house", "!", "AuctionHouseTab"},
            {"ah", "", "AuctionHouseTab", "AhFlipperTab", "AhPriceCopierTab"},
            {"bazaar", "!", "BazaarTab"},
            {"bz", "", "BazaarTab", "BazaarAdvisorTab", "BazaarFlipTab"},
            {"inventory theme", "!", "InventoryThemeTab"},
            {"inv search", "", "InventorySearchTab"},
            {"rarity backgrounds", "", "ItemRarityTab"},
            {"split timers", "!", "SplitTimersTab"},
            {"splits", "", "SplitTimersTab"},
            {"leap menu", "!", "LeapMenuTab"},
            {"spirit leap", "", "LeapMenuTab", "FastLeapTab"},
            {"crosshair", "", "CrosshairTab"},
            {"free look", "", "FreelookTab"},
            {"chat copy", "", "CopyChatTab"},
            {"party finder", "", "CustomPartyFinderTab", "DungeonQueueTab"},
            {"health bar", "", "HealthAndManaBarsTab"},
            {"hp bar", "", "HealthAndManaBarsTab"},
            {"mana", "", "HealthAndManaBarsTab"},
            {"streamer", "", "StreamerModeTab"},
            {"discord", "", "DiscordRpcTab"},
            {"youtube", "", "ShortsTab"},
            {"hoppity", "", "ChocolateFactoryTab"},
            {"chest profit", "", "CroesusTab"},
            {"storage", "", "StorageOverlayTab", "StorageSearchTab"},
            {"pets", "", "PetWheelTab"},
            {"livid", "", "LividSolverTab"},
            {"water board", "", "WaterSolverTab"},
            {"creeper beams", "", "BeamsSolverTab"},
            {"tic tac toe", "", "TicTacToeSolverTab"},
            {"gyro", "", "GyroOverlayTab"},
            {"class colour", "", "ClassColorsTab"},
            {"teammate glow", "", "TeammatesTab"},
            {"fairy souls", "", "PathfindingTab", "AutoFairySoulsTab"},
            {"boss health", "!", "BossHealthTab"},
            {"motion blur", "!", "MotionBlurTab"},
            // cheat jar only (skipped where the card is not in the menu)
            {"auto routes", "!", "AutoRoutesTab"},
            {"trigger bot", "", "SecretTriggerbotTab", "TerminalTriggerbotTab", "GoldorTriggerbotTab", "ThornTriggerbotTab",
                    "AutoSimonSaysTab", "AutoBloodCampTab"},
            {"auto terms", "", "AutoTerminalTab"},
            {"breaker aura", "!", "BreakerAuraTab"},
            // killer560 2026-11-02: "i search invsort and the inventory sorter doesnt show up even tho it is the command"
            {"invsort", "!", "InventorySorterTab"},
            {"/invsort", "!", "InventorySorterTab"},
    };

    private record Found(int index, String line) {
    }

    static void searchQueries(UiCase c) {
        boolean hasEngine = Mod.has("gui.MenuSearch");
        Screen s = c.onClient(mc -> {
            try {
                return MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        int[] counts = new int[3]; // ran, passed, skipped
        List<String> table = new ArrayList<>();
        long[] worstKey = {0};
        long[] firstKey = {0};
        try {
            Map<String, Object> leafById = c.onClient(mc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Object t : MenuACases.leaves()) {
                    m.put(t.getClass().getSimpleName(), t);
                }
                return m;
            });
            for (String[] q : QUERIES) {
                List<String> want = new ArrayList<>();
                for (int i = 2; i < q.length; i++) {
                    if (leafById.containsKey(q[i])) {
                        want.add(q[i]);
                    }
                }
                if (want.isEmpty()) {
                    counts[2]++;
                    table.add("SKIP  '" + q[0] + "' - no " + q[2] + " in this jar's menu");
                    continue;
                }
                counts[0]++;
                Found f = c.onClient(mc -> {
                    setSearch(s, q[0]);
                    List<?> results = MenuACases.results(s);
                    StringBuilder top = new StringBuilder();
                    int at = -1;
                    for (int i = 0; i < results.size(); i++) {
                        Object r = results.get(i);
                        String id = MenuACases.tabId(r);
                        if (i < 3) {
                            top.append(i == 0 ? "" : " | ").append(Mod.call(r, "label")).append(" @ ").append(id);
                        }
                        if (at < 0 && want.contains(id)) {
                            at = i;
                        }
                    }
                    return new Found(at, results.size() + " result(s); top: " + top);
                });
                boolean exact = "!".equals(q[1]);
                boolean ok = f.index() >= 0 && (!exact || f.index() < 3);
                if (ok) {
                    counts[1]++;
                }
                table.add((ok ? "PASS  " : "FAIL  ") + "'" + q[0] + "' -> " + String.join("/", want) + " at "
                        + (f.index() < 0 ? "none" : "#" + (f.index() + 1)) + (exact ? " (exact: top 3)" : "") + "; "
                        + f.line());
                // problem, not check: every query runs, so the report lists them all (the before/after table).
                if (!ok) c.problem("search '" + q[0] + "' does not list " + String.join(" or ", want)
                        + (exact ? " in its first three results" : "") + " (" + (f.index() < 0 ? "absent" : "#" + (f.index() + 1)) + ")");
            }
            // Timing: type a few queries one character at a time, as the box does; the first key reads every tab.
            for (String q : new String[]{"wither esp", "hide potion effects", "secret waypoints", "chat keybinds"}) {
                c.onClient(mc -> {
                    setSearch(s, "");
                    return null;
                });
                for (int i = 1; i <= q.length(); i++) {
                    String prefix = q.substring(0, i);
                    long ns = c.onClient(mc -> {
                        long t0 = System.nanoTime();
                        setSearch(s, prefix);
                        return System.nanoTime() - t0;
                    });
                    if (i == 1) {
                        firstKey[0] = Math.max(firstKey[0], ns);
                    } else {
                        worstKey[0] = Math.max(worstKey[0], ns);
                    }
                }
            }
            String engine = hasEngine ? String.format(Locale.ROOT, "; index build %.1f ms (%s builds), last query %.2f ms",
                    ((Number) Mod.field("gui.MenuSearch", "lastIndexNanos")).longValue() / 1e6,
                    Mod.field("gui.MenuSearch", "indexBuilds"),
                    ((Number) Mod.field("gui.MenuSearch", "lastQueryNanos")).longValue() / 1e6) : "; old search (no MenuSearch)";
            c.note(String.format(Locale.ROOT, "keystroke timing: first key %.1f ms, worst later key %.1f ms%s",
                    firstKey[0] / 1e6, worstKey[0] / 1e6, engine));
            c.check(worstKey[0] < 50_000_000L, String.format(Locale.ROOT, "a keystroke after the first took %.1f ms (bound 50 ms)",
                    worstKey[0] / 1e6));
            c.check(firstKey[0] < 3_000_000_000L, String.format(Locale.ROOT, "the first keystroke took %.1f ms (bound 3 s)",
                    firstKey[0] / 1e6));
        } finally {
            c.onClient(mc -> {
                setSearch(s, "");
                McCompat.setScreen(mc, null);
                return null;
            });
            for (String line : table) {
                c.note(line);
            }
            c.note(String.format(Locale.ROOT, "queries: %d ran, %d found their card, %d skipped (card not in this jar)",
                    counts[0], counts[1], counts[2]));
        }
        c.check(counts[0] >= 30, "only " + counts[0] + " queries ran - the list or the menu is not what it should be");
    }

    // ---- 1102-ui-search-commands --------------------------------------------------------------------------------------

    /** The first result index (or -1) whose tab is {@code id}, after typing {@code q}. */
    private static int rank(Screen s, String q, String id) {
        setSearch(s, q);
        List<?> results = MenuACases.results(s);
        for (int i = 0; i < results.size(); i++) {
            if (MenuACases.tabId(results.get(i)).equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * killer560 (2026-11-02): "i search invsort and the inventory sorter doesnt show up even tho it is the command". Every
     * root chat command the mod registered (its handler's class is the mod's) has an owner card ({@code gui.SearchCommands})
     * or a reason in NO_CARD; typing it, bare and with "/", lists that card in the first three results.
     */
    @SuppressWarnings("unchecked")
    static void searchCommands(UiCase c) {
        if (!Mod.has("gui.SearchCommands")) {
            c.skipCase("the jar has no gui.SearchCommands (mod effects-search-1102)");
            return;
        }
        Screen s = c.onClient(mc -> {
            try {
                return MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        int[] n = new int[4]; // commands, found, no card, owner not in this jar
        try {
            Map<String, String> owners = c.onClient(mc -> (Map<String, String>) Mod.staticCall("gui.SearchCommands", "owners"));
            Map<String, String> noCard = (Map<String, String>) Mod.field("gui.SearchCommands", "NO_CARD");
            Map<String, Object> leafById = c.onClient(mc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Object t : MenuACases.leaves()) {
                    m.put(t.getClass().getSimpleName(), t);
                }
                return m;
            });
            c.check(owners.size() > 30, "only " + owners.size() + " mod commands on the dispatcher: " + owners.keySet());
            for (Map.Entry<String, String> e : owners.entrySet()) {
                String cmd = e.getKey();
                String owner = e.getValue();
                n[0]++;
                if (owner == null) {
                    if (noCard.containsKey(cmd)) {
                        n[2]++;
                        c.note("no card  /" + cmd + " (" + noCard.get(cmd) + ")");
                    } else {
                        c.problem("/" + cmd + " has no owner card and no NO_CARD reason");
                    }
                    continue;
                }
                if (!leafById.containsKey(owner)) {
                    n[3]++;
                    c.note("skip     /" + cmd + " -> " + owner + " (card not in this jar's menu)");
                    continue;
                }
                int bare = c.onClient(mc -> rank(s, cmd, owner));
                int slash = c.onClient(mc -> rank(s, "/" + cmd, owner));
                boolean ok = bare >= 0 && bare < 3 && slash >= 0 && slash < 3;
                if (ok) {
                    n[1]++;
                }
                c.note((ok ? "PASS     " : "FAIL     ") + "/" + cmd + " -> " + owner + " at #" + (bare + 1) + " bare, #" + (slash + 1)
                        + " with /");
                if (!ok) {
                    c.problem("searching '" + cmd + "' / '/" + cmd + "' does not list " + owner + " in the first three (#"
                            + (bare + 1) + ", #" + (slash + 1) + ")");
                }
            }
        } finally {
            c.onClient(mc -> {
                setSearch(s, "");
                McCompat.setScreen(mc, null);
                return null;
            });
            c.note(String.format(Locale.ROOT, "commands: %d registered by the mod, %d find their card, %d have no card on purpose,"
                    + " %d belong to a card this jar lacks", n[0], n[1], n[2], n[3]));
        }
    }

    // ---- 1102-ui-search-readme ----------------------------------------------------------------------------------------

    /** README names with no card in the menu, and where they are instead. */
    private static final Map<String, String> NO_CARD_NAMES = Map.of(
            "Mod conflict warnings", "a chat warning, no setting",
            "Proxy Client", "main menu, Swap Accounts page",
            "Account Switcher", "main menu, Swap Accounts page",
            "Favorites", "a sidebar page of the menu itself",
            "Menu Memory", "the menu reopening where it was",
            "Reboot", "the /reboot command");

    private static final java.util.regex.Pattern README_LINE = java.util.regex.Pattern.compile("^\\*\\*([^*]{1,60})\\*\\* - (.+)$");

    /**
     * Every feature name in the mod README's name list (the checkout in TESTKIT_MOD_SOURCE / modSource) finds at least one
     * result. The README's "Cheat Build Only" names are skipped on a legit jar.
     */
    static void searchReadme(UiCase c) throws Exception {
        java.nio.file.Path src = dev.testkit.gametest.Machine.modSource();
        if (src == null || !Files.exists(src.resolve("README.md"))) {
            c.skipCase("no mod checkout with a README (TESTKIT_MOD_SOURCE / modSource)");
            return;
        }
        List<String> names = new ArrayList<>();
        boolean cheat = Mod.isCheat();
        for (String line : Files.readAllLines(src.resolve("README.md"), StandardCharsets.UTF_8)) {
            java.util.regex.Matcher m = README_LINE.matcher(line.trim());
            if (!m.matches() || (!cheat && m.group(1).startsWith("Cheat"))) {
                continue;
            }
            for (String part : m.group(2).split(",(?![^(]*\\))")) {
                // "Auto Dialogue / Breaker Aura" is two features in one entry.
                for (String piece : part.split(" / ")) {
                    String name = piece.replace("\u2020", "").replaceAll("\\([^)]*\\)", "").trim();
                    if (!name.isEmpty() && !NO_CARD_NAMES.containsKey(name)) {
                        names.add(name);
                    } else if (!name.isEmpty()) {
                        c.note("no card  " + name + " (" + NO_CARD_NAMES.get(name) + ")");
                    }
                }
            }
        }
        c.note("README " + src.resolve("README.md") + ": " + names.size() + " names" + (cheat ? "" : " (cheat block skipped)"));
        c.check(names.size() > 100, "only " + names.size() + " README names parsed");
        Screen s = c.onClient(mc -> {
            try {
                return MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        int found = 0;
        List<String> missing = new ArrayList<>();
        try {
            for (String name : names) {
                String top = c.onClient(mc -> {
                    setSearch(s, name);
                    List<?> r = MenuACases.results(s);
                    return r.isEmpty() ? null : Mod.call(r.get(0), "label") + " @ " + MenuACases.tabId(r.get(0));
                });
                if (top == null) {
                    missing.add(name);
                } else {
                    found++;
                }
            }
        } finally {
            c.onClient(mc -> {
                setSearch(s, "");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
        c.note("README names: " + found + " of " + names.size() + " find something; nothing for: " + missing);
        for (String m : missing) {
            c.problem("README name '" + m + "' finds nothing in the menu search");
        }
    }

    private static void setSearch(Screen s, String text) {
        ((EditBox) Mod.field(s, "searchField")).setValue(text);
    }
}
