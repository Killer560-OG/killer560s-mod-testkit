package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.screens.Screen;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every part of the mod matches the menu theme (killer560, 2026-11-04: "if someone has the green theme and has my crystal
 * hollows map the border shouldnt be orange it should be green"). Mod side: {@code gui/ThemeAccent} turns each amber
 * literal into the theme's color (unchanged on Amber).
 * <ul>
 *   <li>1104-ui-themematch-pixels: under Amber and then Green, screenshots the Crystal Hollows Map screen, the Edit Held
 *       Item screen and the Interactive (dungeon) Map, and reads the border pixel of each: on Amber it is the amber the
 *       code names (proof the sample hits the border), on Green it is the green theme's color and not amber.
 *       TESTKIT_THEMEMATCH_SHOTS copies the pictures there.</li>
 *   <li>1104-ui-themematch-audit: walks every class in the mod jar and fails on a default-theme orange constant that does
 *       not go straight into {@code ThemeAccent}, in a class not in {@link #ALLOWED} (or more of them than it allows).
 *       A color that means something (rarity, a zone, a room type) or a default the player can change is allowed there
 *       with its reason; a border, outline, title or highlight is not.</li>
 * </ul>
 */
final class ThemeMatchCases {

    private ThemeMatchCases() {
    }

    /** The default (Amber) theme's oranges, RGB: MenuStyle's ORs, the old fixed widget amber and its browns, gold. */
    static final Set<Integer> ORANGES = Set.of(0xCC6600, 0xFF8A1F, 0xFFA040, 0x553311, 0x663D1A, 0xFF8C1A, 0xC76A14,
            0xC06010, 0xFFB060, 0x7A4A18, 0x4A3420, 0xC45F0A, 0xFFA500, 0xFF8C00, 0xFF8800, 0xFFAA00);

    private static final String THEME_ACCENT = "com/killer560/hub/gui/ThemeAccent";

    /** Class (under com.killer560.hub, top level) -> how many raw orange constants it may hold, with why. */
    static final Map<String, Integer> ALLOWED = new TreeMap<>();

    private static void allow(String why, Object... classAndCount) {
        for (int i = 0; i < classAndCount.length; i += 2) {
            ALLOWED.put((String) classAndCount[i], (Integer) classAndCount[i + 1]);
        }
    }

    static {
        // The themes themselves: Amber's definition, the presets, the main menu's and the Inventory Theme's own looks,
        // and the Profile Viewer's Amber start values (syncTheme replaces them every frame) and its fixed dark tooltip.
        allow("theme definitions", "gui.MenuStyle", 9, "gui.MenuTheme", 2, "gui.PanelTheme", 5, "livemap.MapThemes", 2,
                "bazaar.BazaarTheme", 3, "auction.ah.AhTheme", 1, "mainmenu.MenuChrome", 6, "mainmenu.MenuWidgets", 4,
                "inventorytheme.InventoryThemeConfig", 1, "profileviewer.screen.ProfileViewerScreen", 8,
                "profileviewer.ProfileViewerConfig", 1); // the /pv Styles' palettes (NEU Classic is NEU's own gold)
        // Stored as the amber constant, mapped onto the theme where it is drawn (ProfitPanels.themed,
        // ChestProfitFeature.themed).
        allow("mapped at draw", "gui.profit.ProfitPanels", 1, "gui.profit.BazaarFlipProfitScreen", 1,
                "gui.profit.ExperimentsProfitScreen", 1, "gui.profit.MiningProfitScreen", 2, "gui.profit.NucleusProfitScreen", 1,
                "croesus.CroesusTrackerScreen", 3, "luck.LuckScreen", 2, "croesus.ChestProfitFeature", 15);
        // A default the player changes with a color picker (the config default and the picker's reset value).
        allow("player-picked defaults", "ap3.Ap3Config", 4, "arrowalign.ArrowAlignConfig", 2, "autoroutes.AutoRoutesConfig", 2,
                "croesus.CroesusConfig", 1, "dungeonalerts.DungeonAlertsConfig", 2, "dungeonalerts.RoomAlerts", 1,
                "dungeonextras.DungeonExtrasConfig", 1, "enchantcolors.EnchantColorsConfig", 2, "f7spots.F7SpotsConfig", 2,
                "heldoutline.HeldOutlineConfig", 2, "itemprotect.ItemProtectConfig", 2, "lagdisplay.LagDisplayConfig", 1,
                "livemap.LiveMapConfig", 3, "miningqol.MiningQolConfig", 1, "mobesp.MobEspConfig", 4, "p3nav.P3NavConfig", 4,
                "pathfinding.PathfindingConfig", 5, "playerstats.PlayerStatsConfig", 1, "posmsg.PosmsgEntry", 1,
                "powdermining.PowderMiningConfig", 1, "realtime.RealTimeConfig", 1, "routes.Route", 1, "routes.RouteStore", 1,
                "scoreboard.CustomScoreboardConfig", 4, "scoreboard.ScoreboardEditorScreen", 2, "scorecalc.ScoreCalculatorConfig", 2,
                "secretwaypoints.SecretWaypointsConfig", 1, "simonsays.SimonSaysConfig", 2, "slotbinds.SlotBindsConfig", 2,
                "spotify.SongShareConfig", 2, "terminals.TerminalSolverConfig", 9, "thorn.ThornConfig", 4,
                "trail.RibbonTrailConfig", 1, "gui.CapeDesignerScreen", 1, "gui.tab.Ap3Tab", 1, "gui.tab.ArrowAlignTab", 1,
                "gui.tab.CroesusTab", 1, "gui.tab.CustomMageBeamTab", 1, "gui.tab.DungeonAlertsTab", 2,
                "gui.tab.EnchantColorsTab", 1, "gui.tab.F7SpotsTab", 1, "gui.tab.HealthAndManaBarsTab", 1,
                "gui.tab.HeldOutlineTab", 2, "gui.tab.ItemProtectTab", 1, "gui.tab.LagDisplayTab", 1, "gui.tab.LiveMapTab", 1,
                "gui.tab.MiningRaffleTab", 1, "gui.tab.MobEspTab", 1, "gui.tab.P3NavTab", 2, "gui.tab.PathfindingTab", 2,
                "gui.tab.PosmsgTab", 1, "gui.tab.RealTimeTab", 1, "gui.tab.RibbonTrailTab", 1, "gui.tab.ScoreCalculatorTab", 2,
                "gui.tab.SecretWaypointsTab", 1, "gui.tab.SimonSaysTab", 1, "gui.tab.SlotBindsTab", 1, "gui.tab.SpotifyF5Rows", 1,
                "gui.tab.ThornTab", 2, "gui.tab.TreasureChestHighlightTab", 1, "gui.tab.WaypointRoutesTab", 1,
                "mining.deployables.MiningDeployables", 2, "gui.tab.MiningDeployablesTab", 1);
        // A color that means something: rarity / Minecraft gold, a zone, a dragon or relic, a state, a warning mark.
        allow("meaning", "itemrarity.ItemRarity", 1, "profileviewer.data.LevelTables", 1, "profileviewer.screen.ExtraPages", 2,
                "util.ChatColors", 1, "slottext.SlotTextFeature", 3, "miningqol.CrystalHollowsWalls", 1,
                "witherdragons.KingRelic", 1, "witherdragons.WitherDragon", 1, "blessings.Blessing", 1,
                "chocolatefactory.egg.EggEsp", 1, "chocolatefactory.ChocolateFactoryHelper", 1, "bridge.BridgeStatus", 3,
                "modusers.ModUserTag", 1, "p3status.P3StatusFeature", 1, "livemap.MapPainter", 7, "runsummary.RunMapPainter", 1,
                "abilitycooldown.AbilityCooldownFeature", 1, "maskinvincibility.MaskInvincibilityFeature", 1,
                "lagdisplay.LagDisplayFeature", 1, "miningqol.CommissionClaim", 1, "dvd.DvdFeature", 1,
                "gui.SectionHeaders", 1, "bloodrush.BloodRushHelperFeature", 2, "experiments.ExperimentsFeature", 2,
                "arrowalign.ArrowAlignFeature", 1, "f7spots.AimSpots", 2);
        // Drawn in the world (nodes, paths, waypoints), not the mod's GUI chrome.
        allow("world render", "livemap.autoclear.ClearExecutor", 2, "livemap.autoclear.ClearNode", 1,
                "pathfinding.EtherwarpHopper", 1, "pathfinding.PathWorldRenderer", 1, "routes.WaypointRoutesFeature", 1);
    }

    // ---- pixels ---------------------------------------------------------------------------------------------------

    static void pixels(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        try {
            int[] amber = sampleAll(c, "AMBER");
            int[] green = sampleAll(c, "GREEN");
            int greenAccent = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("gui.OverlayThemes", "menu"), "accent"));
            c.note("green theme accent " + MenuACases.hex(greenAccent));
            String[] what = {"Crystal Hollows Map border", "Edit Held Item edge line", "Interactive Map frame ring"};
            int[] amberWant = {0xCC6600, 0xCC6600, -1};
            for (int i = 0; i < what.length; i++) {
                c.note(what[i] + ": Amber " + MenuACases.hex(amber[i]) + ", Green " + MenuACases.hex(green[i]));
                if (amber[i] == Integer.MIN_VALUE || green[i] == Integer.MIN_VALUE) {
                    c.problem(what[i] + ": no sample (screen did not open or lay out)");
                    continue;
                }
                if (amberWant[i] >= 0) {
                    c.check(near(amber[i], amberWant[i]), what[i] + " on Amber is " + MenuACases.hex(amber[i])
                            + ", not " + MenuACases.hex(amberWant[i]) + ": the sample misses the border");
                }
                c.check(!ORANGES.contains(green[i] & 0xFFFFFF), what[i] + " is still amber on Green: " + MenuACases.hex(green[i]));
                c.check(GreenThemeSuite.green(green[i]), what[i] + " on Green is not green: " + MenuACases.hex(green[i]));
                c.check(green[i] != amber[i], what[i] + " did not change between Amber and Green");
            }
            c.check(near(green[0], greenAccent) && near(green[1], greenAccent), "the CH map border / held item line are not the"
                    + " green accent " + MenuACases.hex(greenAccent));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                return null;
            });
        }
    }

    /** {CH map border, held item edge, interactive map frame ring} under {@code theme}; MIN_VALUE = not sampled. */
    private static int[] sampleAll(UiCase c, String theme) throws Exception {
        c.onClient(mc -> {
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme));
            return null;
        });
        int[] out = new int[3];
        // Crystal Hollows Map: the renderer's outline sits one pixel outside its panel.
        Screen ch = open(c, "mining.chmap.CrystalHollowsMapScreen");
        BufferedImage img = shot(c, "chmap-" + theme.toLowerCase());
        int[] r = c.onClient(mc -> {
            Object renderer = Mod.field(ch, "renderer");
            return new int[]{(Integer) Mod.field(renderer, "x0"), (Integer) Mod.field(renderer, "y0"),
                    (Integer) Mod.field(renderer, "x1"), (Integer) Mod.field(renderer, "y1")};
        });
        out[0] = r[2] > r[0] ? pixel(img, ch, r[0] - 1, (r[1] + r[3]) / 2) : Integer.MIN_VALUE;
        // Edit Held Item: a one-pixel line down the column's inner edge.
        Screen held = open(c, "helditem.HeldItemEditScreen", (Object) null);
        img = shot(c, "helditem-" + theme.toLowerCase());
        int[] h = c.onClient(mc -> {
            int w = Math.min(200, Math.max(80, held.width / 2 - 12));
            int px = (Integer) Mod.field(held, "panelX");
            boolean left = (Boolean) Mod.field(held, "panelLeft");
            return new int[]{left ? px + w - 1 : px, held.height - 3};
        });
        out[1] = pixel(img, held, h[0], h[1]);
        // Interactive (dungeon) Map: the theme's frame ring two pixels outside the map.
        Screen map = c.onClient(mc -> {
            try {
                Screen s = (Screen) Mod.cls("livemap.InteractiveMapScreen").getConstructor(boolean.class).newInstance(false);
                McCompat.setScreen(mc, s);
                return s;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(5);
        img = shot(c, "dungeonmap-" + theme.toLowerCase());
        int[] p = c.onClient(mc -> (int[]) Mod.call(map, "panel"));
        out[2] = p[2] > p[0] ? pixel(img, map, p[0] - 2, (p[1] + p[3]) / 2) : Integer.MIN_VALUE;
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        return out;
    }

    private static Screen open(UiCase c, String cls, Object... args) {
        Screen s = c.onClient(mc -> {
            try {
                Screen screen;
                if (args.length == 0) {
                    screen = (Screen) Mod.cls(cls).getConstructor().newInstance();
                } else {
                    screen = (Screen) Mod.cls(cls).getConstructor(Screen.class).newInstance(args);
                }
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(5);
        return s;
    }

    private static BufferedImage shot(UiCase c, String label) {
        BufferedImage img = InvBatchCases.shot(c, label);
        String dir = System.getenv("TESTKIT_THEMEMATCH_SHOTS");
        if (dir != null && !dir.isBlank()) {
            try {
                String mcv = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion()
                        .getFriendlyString();
                Path out = Path.of(dir).resolve(label + "-" + mcv + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png");
                Files.createDirectories(out.getParent());
                javax.imageio.ImageIO.write(img, "png", out.toFile());
            } catch (Exception e) {
                c.note("could not copy " + label + ": " + e);
            }
        }
        return img;
    }

    /** The screenshot's pixel at GUI point (gx, gy) of {@code s}, as 0xFFRRGGBB. */
    private static int pixel(BufferedImage img, Screen s, int gx, int gy) {
        double f = img.getWidth() / (double) s.width;
        int x = (int) Math.min(img.getWidth() - 1, Math.max(0, Math.floor((gx + 0.5) * f)));
        int y = (int) Math.min(img.getHeight() - 1, Math.max(0, Math.floor((gy + 0.5) * f)));
        return img.getRGB(x, y) | 0xFF000000;
    }

    private static boolean near(int a, int b) {
        for (int sh = 0; sh <= 16; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 3) {
                return false;
            }
        }
        return true;
    }

    // ---- source audit (bytecode) ----------------------------------------------------------------------------------

    static void audit(UiCase c) throws Exception {
        ModContainer mod = FabricLoader.getInstance().getModContainer("killer560smod")
                .orElseThrow(() -> new AssertionError("killer560smod is not loaded"));
        Map<String, Integer> raw = new TreeMap<>();
        int classes = 0;
        int wrapped = 0;
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
                String key = top.substring("com/killer560/hub/".length()).replace('/', '.');
                ClassNode cn = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(cn, ClassReader.SKIP_FRAMES);
                classes++;
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                        // 0xFFFFxxxx colors (0xFFFFAA00, 0xFFFFA500...) fit a short, so javac pushes them with SIPUSH.
                        Integer v = in instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i ? i
                                : in instanceof IntInsnNode push && push.getOpcode() == Opcodes.SIPUSH ? push.operand : null;
                        if (v == null || !ORANGES.contains(v & 0xFFFFFF)) {
                            continue;
                        }
                        AbstractInsnNode next = in.getNext();
                        while (next != null && next.getOpcode() < 0) {
                            next = next.getNext();
                        }
                        if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                                && call.owner.equals(THEME_ACCENT)) {
                            wrapped++;
                        } else {
                            raw.merge(key, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        c.note(classes + " classes, " + wrapped + " orange constants go through ThemeAccent, "
                + raw.values().stream().mapToInt(Integer::intValue).sum() + " raw in " + raw.size() + " classes");
        c.check(classes > 500, "only " + classes + " classes walked: not the mod jar");
        c.check(wrapped > 20, "only " + wrapped + " ThemeAccent calls found: the audit does not see them");
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            list.append('"').append(e.getKey()).append("\", ").append(e.getValue()).append(", ");
            Integer cap = ALLOWED.get(e.getKey());
            if (cap == null || e.getValue() > cap) {
                c.problem(e.getKey() + " holds " + e.getValue() + " raw default-orange constant(s)"
                        + (cap == null ? "" : " (allowed " + cap + ")")
                        + ": draw it through gui/ThemeAccent, or allow it in ThemeMatchCases.ALLOWED with the reason");
            }
        }
        c.note("raw: " + list);
    }
}
