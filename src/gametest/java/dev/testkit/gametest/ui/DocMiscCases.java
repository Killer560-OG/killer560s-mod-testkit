package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * killer560's 2026-10-13 doc list, misc items (mod branch doc-misc):
 * <ul>
 * <li>1311 Custom Scoreboard numbers: a REAL sidebar on the integrated server (team prefix/suffix lines, split the ways a
 * server splits them) carrying 114,000 / 1,140 / 14,000 / 1,200,000 in Purse, Bits, Motes and Copper; the mod reads the
 * sidebar itself and its Purse/Bits/Motes/Copper lines must show the full number in Long and Short format.</li>
 * <li>1312 Inventory Theme slot outlines: every slot of a six-row chest (its player inventory included) and the hotbar,
 * in Default / Dark / Mod Theme / Custom, has its line on all four sides, read off screenshots; the top/left line stands
 * out from the panel and the slot fill. Pictures to the Misc screenshot folder.</li>
 * <li>1313 Health and Mana Bars a hair thinner by default: a fresh config draws every bar 12 thick (was 14), a chosen
 * Bar Height above it and Show Value off are drawn as set.</li>
 * <li>1314 Peek Chat: the bound key (real key input) opens a read-only chat - no input line drawn (pixels, against plain
 * chat as the control), typing and Enter do nothing and send nothing, the wheel and Page Up scroll it, a link is
 * clickable, Escape and the key close it, a saved draft survives.</li>
 * <li>1315 Loadout Keybinds' Open Loadouts button, pressed with the real mouse, sends the "loadouts" command packet.</li>
 * </ul>
 */
final class DocMiscCases {

    private static final Path SHOTS = Path.of("C:/Users/Hunter/OneDrive/Desktop/Mod Screenshots/Misc (2026-10-13)");
    private static final String SB = "scoreboard.CustomScoreboardConfig";
    private static final String ICFG = "inventorytheme.InventoryThemeConfig";
    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String PEEK = "peekchat.PeekChatConfig";
    /** GLFW_KEY_KP_6: bound to nothing in vanilla or the mod's defaults. */
    private static final int PEEK_KEY = 326;

    private DocMiscCases() {
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    private static String mcv() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
    }

    /** A screenshot (chat NOT cleared), kept in the report and copied to {@link #SHOTS}. */
    private static BufferedImage shot(UiCase c, String label) {
        InvStorageCases.awaitNoLoadingOverlay(c);
        c.ticks(2);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        Path src = kept != null ? kept : taken;
        try {
            Files.createDirectories(SHOTS);
            Files.copy(src, SHOTS.resolve(label + "-" + mcv() + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return javax.imageio.ImageIO.read(src.toFile());
        } catch (Exception e) {
            throw new AssertionError("could not read the screenshot " + src + ": " + e, e);
        }
    }

    private static int rgb(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return -1;
        }
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    /** A real key press: held for 3 ticks (the mod polls keys at the end of a tick), then released. */
    private static void press(UiCase c, int key) {
        c.ctx().getInput().holdKey(key);
        c.ticks(3);
        c.ctx().getInput().releaseKey(key);
        c.ticks(3);
    }

    // =================================================================================================================
    // 1311 Custom Scoreboard numbers
    // =================================================================================================================

    private static final String TEAM = "k560docmisc";

    /** The sidebar as a server shows it: one invisible owner per line, the text in its team's prefix (and suffix from
     *  {@code split}, the suffix repeating the colour in force when {@code repeatColour}). */
    private static void board(UiCase c, List<String> lines, int split, boolean repeatColour) {
        c.onClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerScoreboard sb = server.getScoreboard();
                Objective old = sb.getObjective(TEAM);
                if (old != null) {
                    sb.removeObjective(old);
                }
                for (PlayerTeam t : new ArrayList<>(sb.getPlayerTeams())) {
                    if (t.getName().startsWith(TEAM)) {
                        sb.removePlayerTeam(t);
                    }
                }
                Objective obj = sb.addObjective(TEAM, ObjectiveCriteria.DUMMY, Component.literal("SKYBLOCK"),
                        ObjectiveCriteria.RenderType.INTEGER, false, null);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String owner = "§" + Integer.toHexString(i) + "§r";
                    PlayerTeam team = sb.addPlayerTeam(TEAM + i);
                    if (split > 0 && split < line.length()) {
                        String pre = line.substring(0, split);
                        String suf = line.substring(split);
                        if (repeatColour) {
                            int at = pre.lastIndexOf('§');
                            if (at >= 0 && at + 1 < pre.length()) {
                                suf = pre.substring(at, at + 2) + suf;
                            }
                        }
                        team.setPlayerPrefix(Component.literal(pre));
                        team.setPlayerSuffix(Component.literal(suf));
                    } else {
                        team.setPlayerPrefix(Component.literal(line));
                    }
                    sb.addPlayerToTeam(owner, team);
                    sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), obj).set(lines.size() - i);
                }
                sb.setDisplayObjective(DisplaySlot.SIDEBAR, obj);
            });
            return null;
        });
        c.ticks(6);
    }

    private static void clearBoard(UiCase c) {
        c.onClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerScoreboard sb = server.getScoreboard();
                Objective old = sb.getObjective(TEAM);
                if (old != null) {
                    sb.removeObjective(old);
                }
                for (PlayerTeam t : new ArrayList<>(sb.getPlayerTeams())) {
                    if (t.getName().startsWith(TEAM)) {
                        sb.removePlayerTeam(t);
                    }
                }
            });
            return null;
        });
        c.ticks(4);
    }

    /** The mod's own reading: the sidebar read now, then each entry's drawn lines (colour codes stripped). */
    @SuppressWarnings("unchecked")
    private static List<String> entryLines(UiCase c, String... entries) {
        return c.onClient(mc -> {
            try {
                Mod.staticCall("scoreboard.ScoreboardData", "sidebarNow", mc);
                Object cfg = Mod.cfg(SB);
                Class<?> entryCls = Mod.cls("scoreboard.ScoreboardEntry");
                Method lines = entryCls.getDeclaredMethod("lines", Mod.cls(SB));
                lines.setAccessible(true);
                List<String> out = new ArrayList<>();
                for (String e : entries) {
                    Object constant = Mod.enumValue("scoreboard.ScoreboardEntry", e);
                    for (Object line : (List<Object>) lines.invoke(constant, cfg)) {
                        String text = (String) Mod.call(line, "text");
                        out.add(net.minecraft.ChatFormatting.stripFormatting(text));
                    }
                }
                return out;
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(UiCase.describe(ex), ex);
            }
        });
    }

    // ---- Hypixel's real sidebar shape (scoreboard-digits, 2026-10-13) ----------------------------------------------
    // From killer560's own client logs: Hypixel's line owner is ONE invalid format code ("§i", "§p", "§s"...), not a
    // valid one, and the prefix holds the first 16 characters - "§ewww.hypixel.ne§gt", "§bKiller56§p0",
    // "Starting in: §a0§i:04". stripFormatting leaves an invalid code in, so the mod kept the owner and it landed inside
    // any number crossing character 16: a friend's ~114k Copper and his coins read a digit short.

    private static final String[] HYPIXEL_VALUES = {"1,140", "14,000", "114,250", "1,140,250", "11,402,507", "114,025,073"};
    /** Split offsets from just after the value's colour code: -2 (before the code), then 0..11 (every place in a
     *  number up to 11 characters, and past it). */
    private static final int[] HYPIXEL_OFFSETS = {-2, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int HYPIXEL_SPLITS = HYPIXEL_OFFSETS.length;
    /** Invalid format codes, as Hypixel's owners use. */
    private static final String INVALID_CODES = "ijpqstuvwxyzgh";

    private static void boardHypixel(UiCase c, List<String> lines, int offset, boolean repeatColour) {
        c.onClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerScoreboard sb = server.getScoreboard();
                Objective old = sb.getObjective(TEAM);
                if (old != null) {
                    sb.removeObjective(old);
                }
                for (PlayerTeam t : new ArrayList<>(sb.getPlayerTeams())) {
                    if (t.getName().startsWith(TEAM)) {
                        sb.removePlayerTeam(t);
                    }
                }
                Objective obj = sb.addObjective(TEAM, ObjectiveCriteria.DUMMY, Component.literal("SKYBLOCK"),
                        ObjectiveCriteria.RenderType.INTEGER, false, null);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int valueAt = line.indexOf(": §") + 4; // just after the value's colour code
                    int s = Math.max(0, Math.min(line.length(), valueAt + offset));
                    if (s > 0 && line.charAt(s - 1) == '§') {
                        s++; // a server never splits a code from its character
                    }
                    String pre = line.substring(0, s);
                    String suf = line.substring(s);
                    if (repeatColour) {
                        int at = pre.lastIndexOf('§');
                        if (at >= 0 && at + 1 < pre.length()) {
                            suf = pre.substring(at, at + 2) + suf;
                        }
                    }
                    String owner = "§" + INVALID_CODES.charAt(i);
                    PlayerTeam team = sb.addPlayerTeam(TEAM + i);
                    team.setPlayerPrefix(Component.literal(pre));
                    team.setPlayerSuffix(Component.literal(suf));
                    sb.addPlayerToTeam(owner, team);
                    sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), obj).set(lines.size() - i);
                }
                sb.setDisplayObjective(DisplaySlot.SIDEBAR, obj);
            });
            return null;
        });
        c.ticks(6);
    }

    /** The mod's Short format (ScoreboardLine.shortFormat), for the expected value. */
    private static String shortOf(String commas) {
        double v = Double.parseDouble(commas.replace(",", ""));
        String suffix;
        double div;
        if (v >= 1e9) {
            suffix = "B";
            div = 1e9;
        } else if (v >= 1e6) {
            suffix = "M";
            div = 1e6;
        } else if (v >= 1e3) {
            suffix = "k";
            div = 1e3;
        } else {
            return String.valueOf((long) v);
        }
        String n = String.format(Locale.US, "%.1f", v / div);
        return (n.endsWith(".0") ? n.substring(0, n.length() - 2) : n) + suffix;
    }

    /** Purse (with an earned "(+1,234)"), Bits, Motes, Copper (with a "(+1,234)") and Gems in Hypixel's line shape,
     *  split at every offset, with and without the colour repeated; Long and Short. Returns the rounds run. */
    @SuppressWarnings("unchecked")
    private static int hypixelShapes(UiCase c) {
        int rounds = 0;
        int failures = 0;
        String[] names = {"Purse", "Bits", "Motes", "Copper", "Gems"};
        for (String v : HYPIXEL_VALUES) {
            List<String> lines = List.of("Purse: §6" + v + " §e(+1,234)", "Bits: §b" + v, "Motes: §d" + v,
                    "Copper: §c" + v + " §7(+1,234)", "Gems: §a" + v);
            for (boolean repeat : new boolean[]{false, true}) {
                for (int offset : HYPIXEL_OFFSETS) {
                    boardHypixel(c, lines, offset, repeat);
                    String label = v + " split +" + offset + (repeat ? " color repeated" : "");
                    List<String> sidebar = c.onClient(mc ->
                            (List<String>) Mod.staticCall("scoreboard.ScoreboardData", "sidebarNow", mc));
                    for (String line : lines) {
                        if (!sidebar.contains(line)) {
                            failures++;
                            if (failures <= 40) {
                                c.problem(label + ": the sidebar line '" + line + "' was read as one of " + sidebar);
                            }
                        }
                    }
                    for (String fmt : new String[]{"LONG", "SHORT"}) {
                        c.onClient(mc -> {
                            Mod.call(Mod.cfg(SB), "setNumberFormat", Mod.enumValue(SB + "$NumberFormat", fmt));
                            return null;
                        });
                        List<String> got = entryLines(c, "PURSE", "BITS", "MOTES", "COPPER", "GEMS");
                        String want = fmt.equals("LONG") ? v : shortOf(v);
                        for (String name : names) {
                            String line = got.stream().filter(l -> l.startsWith(name + ":")).findFirst().orElse(null);
                            String num = line == null ? null : line.substring(name.length() + 1).trim().split("/")[0].trim();
                            if (!want.equals(num)) {
                                failures++;
                                if (failures <= 40) {
                                    c.problem(label + " " + fmt + ": " + name + " reads '" + num + "' (line " + line
                                            + "), want " + want);
                                }
                            }
                        }
                    }
                    rounds++;
                    if (offset == 7 && !repeat) {
                        c.note(label + ": sidebar " + sidebar);
                    }
                }
            }
        }
        c.note("Hypixel shape: " + rounds + " boards (6 values of 4-9 digits x 13 split offsets x color repeated or"
                + " not, owner = one invalid code), each read in Long and Short; " + failures + " wrong reading(s)");
        c.check(failures == 0, failures + " wrong reading(s) of Hypixel-shaped lines");
        return rounds;
    }

    static void scoreboardNumbers(UiCase c) throws Exception {
        Object oldFormat = c.onClient(mc -> Mod.call(Mod.cfg(SB), "getNumberFormat"));
        Object oldDisplay = c.onClient(mc -> Mod.call(Mod.cfg(SB), "getNumberDisplayFormat"));
        Object oldHide = c.onClient(mc -> Mod.call(Mod.cfg(SB), "isHideEmptyLines"));
        Object oldEnabled = c.onClient(mc -> Mod.call(Mod.cfg(SB), "isEnabled"));
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(SB);
                Mod.call(cfg, "setNumberDisplayFormat", Mod.enumValue(SB + "$NumberDisplayFormat", "TEXT_COLOR_NUMBER"));
                Mod.call(cfg, "setHideEmptyLines", false);
                return null;
            });
            // ---- the number helpers on their own --------------------------------------------------------------------
            String[][] parse = {{"114k", "114000"}, {"114,000", "114000"}, {"1,140", "1140"}, {"14,000", "14000"},
                    {"1.2m", "1200000"}, {"1.2M", "1200000"}};
            for (String[] p : parse) {
                Object v = c.onClient(mc -> Mod.staticCall("scoreboard.ScoreboardLine", "parse", p[0]));
                c.note("parse " + p[0] + " -> " + v);
                c.check(v != null && ((Double) v).longValue() == Long.parseLong(p[1]), "parse(" + p[0] + ") = " + v
                        + ", want " + p[1]);
            }
            // ---- a real sidebar, read by the mod, in both number formats -----------------------------------------
            String[][] values = {{"114,000", "114,000", "114k"}, {"1,140", "1,140", "1.1k"}, {"14,000", "14,000", "14k"},
                    {"1,200,000", "1,200,000", "1.2M"}};
            int rounds = 0;
            for (String[] v : values) {
                List<String> lines = List.of("§fPurse: §6" + v[0], "Bits: §b" + v[0], "§fMotes: §d" + v[0],
                        "§fCopper: §c" + v[0]);
                // Whole line in the prefix; split after the label; split after the colour code; split inside the
                // number with and without the colour repeated at the start of the suffix (the 16-char-prefix style).
                int[][] splits = {{0, 0}, {7, 0}, {9, 0}, {10, 0}, {10, 1}, {12, 1}};
                for (int[] sp : splits) {
                    board(c, lines, sp[0], sp[1] == 1);
                    for (String fmt : new String[]{"LONG", "SHORT"}) {
                        c.onClient(mc -> {
                            Mod.call(Mod.cfg(SB), "setNumberFormat", Mod.enumValue(SB + "$NumberFormat", fmt));
                            return null;
                        });
                        List<String> got = entryLines(c, "PURSE", "BITS", "MOTES", "COPPER");
                        String want = fmt.equals("LONG") ? v[1] : v[2];
                        String label = v[0] + " split " + sp[0] + (sp[1] == 1 ? "+colour" : "") + " " + fmt;
                        rounds++;
                        boolean ok = got.size() >= 4;
                        for (String name : new String[]{"Purse", "Bits", "Motes", "Copper"}) {
                            String line = got.stream().filter(l -> l.startsWith(name + ":")).findFirst().orElse(null);
                            // Bits shows "N/available" when it knows the available bits; the number is what is before it.
                            String num = line == null ? null : line.substring(name.length() + 1).trim().split("/")[0].trim();
                            if (!want.equals(num)) {
                                ok = false;
                                c.problem(label + ": " + name + " reads '" + num + "' (line " + line + "), want " + want);
                            }
                        }
                        if (ok && (sp[0] == 0 || v[0].equals("114,000"))) {
                            c.note(label + " -> " + got);
                        }
                    }
                }
            }
            int hypixelRounds = hypixelShapes(c);
            // ---- the board as drawn: the Custom Scoreboard on, over a 114,000 sidebar, a picture to look at ----
            board(c, List.of("§fPurse: §6114,000", "Bits: §b114,000", "§fMotes: §d114,000", "§fCopper: §c114,000"), 0, false);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SB), "setNumberFormat", Mod.enumValue(SB + "$NumberFormat", "SHORT"));
                Mod.call(Mod.cfg(SB), "setEnabled", true);
                return null;
            });
            c.ticks(15);
            String drawn = c.onClient(mc -> {
                List<?> cur = (List<?>) Mod.field("scoreboard.CustomScoreboardFeature", "current");
                List<String> out = new ArrayList<>();
                for (Object l : cur) {
                    out.add(net.minecraft.ChatFormatting.stripFormatting((String) Mod.call(l, "text")));
                }
                return String.valueOf(out);
            });
            shot(c, "scoreboard-114k");
            c.note("Custom Scoreboard drawn lines (Short): " + drawn);
            c.check(rounds == 48, "only " + rounds + " of 48 board/format rounds ran");
            c.note(rounds + " rounds: 4 values x 6 line splits x Long/Short, Purse/Bits/Motes/Copper read from a real sidebar");
            c.check(hypixelRounds == HYPIXEL_VALUES.length * 2 * HYPIXEL_SPLITS, "only " + hypixelRounds
                    + " Hypixel-shape rounds ran");
        } finally {
            clearBoard(c);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(SB);
                Mod.call(cfg, "setNumberFormat", oldFormat);
                Mod.call(cfg, "setNumberDisplayFormat", oldDisplay);
                Mod.call(cfg, "setHideEmptyLines", oldHide);
                Mod.call(cfg, "setEnabled", oldEnabled);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1312 Inventory Theme slot outlines
    // =================================================================================================================

    private record Pal(int panel, int slotFill, int slotDark, int slotLight, boolean bevel) {
        int topLeft() {
            return bevel ? slotDark : slotLight;
        }
    }

    private static Pal palette(UiCase c) {
        return c.onClient(mc -> {
            Object p = Mod.call(Mod.cfg(ICFG), "getPalette");
            java.util.function.Function<String, Integer> f = n -> ((Number) Mod.call(p, n)).intValue() & 0xFFFFFF;
            boolean bevel;
            try {
                bevel = (Boolean) Mod.call(p, "bevelSlots");
            } catch (Throwable t) {
                bevel = true;
            }
            return new Pal(f.apply("panel"), f.apply("slotFill"), f.apply("slotDark"), f.apply("slotLight"), bevel);
        });
    }

    private static void preset(UiCase c, String preset) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(ICFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setHypixelOnly", false);
            Mod.call(cfg, "setPreset", Mod.enumValue(ICFG + "$Preset", preset));
            Mod.call(cfg, "setUseCustomAccent", false);
            Mod.call(cfg, "setUseCustomSlotColor", false);
            Mod.call(cfg, "setThemeHotbar", true);
            Mod.call(cfg, "setHotbarScale", 1.0f);
            return null;
        });
    }

    /** Checks the line just outside each side of the 16-unit item box at (sx, sy) (GUI units), plus the corners when
     *  the preset outlines in one colour. Returns a problem or null. */
    private static String sides(BufferedImage img, int sx, int sy, int gs, Pal p) {
        int bx0 = sx * gs;
        int by0 = sy * gs;
        int bx1 = bx0 + 16 * gs - 1;
        int by1 = by0 + 16 * gs - 1;
        int cx = bx0 + 8 * gs;
        int cy = by0 + 8 * gs;
        int l = rgb(img, bx0 - 1, cy);
        int t = rgb(img, cx, by0 - 1);
        int r = rgb(img, bx1 + 1, cy);
        int b = rgb(img, cx, by1 + 1);
        List<String> bad = new ArrayList<>();
        if (l != p.topLeft()) {
            bad.add(String.format(Locale.ROOT, "left #%06X", l));
        }
        if (t != p.topLeft()) {
            bad.add(String.format(Locale.ROOT, "top #%06X", t));
        }
        if (r != p.slotLight()) {
            bad.add(String.format(Locale.ROOT, "right #%06X", r));
        }
        if (b != p.slotLight()) {
            bad.add(String.format(Locale.ROOT, "bottom #%06X", b));
        }
        if (!p.bevel()) {
            int[][] corners = {{bx0 - 1, by0 - 1}, {bx1 + 1, by0 - 1}, {bx0 - 1, by1 + 1}, {bx1 + 1, by1 + 1}};
            for (int[] k : corners) {
                if (rgb(img, k[0], k[1]) != p.slotLight()) {
                    bad.add(String.format(Locale.ROOT, "corner (%d,%d) #%06X", k[0], k[1], rgb(img, k[0], k[1])));
                }
            }
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static void slotOutlines(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(ICFG), "CONFIG_PATH"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 1920, 1080, 3);
            int totalSlots = 0;
            for (String preset : new String[]{"DEFAULT", "DARK", "MOD_THEME", "CUSTOM"}) {
                preset(c, preset);
                Pal p = palette(c);
                String key = preset.toLowerCase(Locale.ROOT);
                c.note(String.format(Locale.ROOT, "%s: panel #%06X, slot fill #%06X, top/left line #%06X, bottom/right #%06X%s",
                        preset, p.panel(), p.slotFill(), p.topLeft(), p.slotLight(), p.bevel() ? " (vanilla bevel)" : " (one-color outline)"));
                // The top/left line must be visible: well apart from the panel round it and the fill inside it.
                int vsPanel = delta(p.topLeft(), p.panel());
                int vsFill = delta(p.topLeft(), p.slotFill());
                c.check(vsPanel > 60 && vsFill > 60, preset + ": the top/left line #" + Integer.toHexString(p.topLeft())
                        + " is hard to see (RGB distance " + vsPanel + " from the panel, " + vsFill + " from the fill)");

                // ---- a six-row chest, its player inventory below ----
                c.onClient(mc -> {
                    ChestMenu menu = ChestMenu.sixRows(0, mc.player.getInventory());
                    McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal("Slot Outlines")));
                    return null;
                });
                c.ticks(6);
                c.ctx().getInput().setCursorPos(0, 0);
                c.ticks(2);
                List<int[]> at = c.onClient(mc -> {
                    AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                    int left = (Integer) Mod.field(s, "leftPos");
                    int top = (Integer) Mod.field(s, "topPos");
                    List<int[]> out = new ArrayList<>();
                    for (Slot slot : s.getMenu().slots) {
                        if (slot.isActive()) {
                            out.add(new int[]{left + slot.x, top + slot.y, slot.index});
                        }
                    }
                    return out;
                });
                int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
                BufferedImage chest = shot(c, "inventory-outline-" + key);
                int wrong = 0;
                String first = null;
                for (int[] s : at) {
                    String bad = sides(chest, s[0], s[1], gs, p);
                    if (bad != null) {
                        wrong++;
                        if (first == null) {
                            first = "slot " + s[2] + ": " + bad;
                        }
                    }
                }
                totalSlots += at.size();
                c.note(preset + " chest: " + at.size() + " slots, " + wrong + " with a missing or wrong side"
                        + (first == null ? "" : "; first " + first));
                c.check(at.size() == 90, preset + ": the chest has " + at.size() + " slots, want 90 (54 + 36)");
                c.check(wrong == 0, preset + ": " + wrong + " chest/inventory slot(s) lack a side: " + first);
                closeScreen(c);

                // ---- the hotbar (slot 0 selected; its ring covers its frame, so 1-8 are measured) ----
                c.onClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(0);
                    return null;
                });
                c.ticks(3);
                int[] g = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
                BufferedImage bar = shot(c, "hotbar-outline-" + key);
                int hotWrong = 0;
                String hotFirst = null;
                boolean vanillaBar = preset.equals("DEFAULT");
                if (!vanillaBar) {
                    for (int i = 2; i < 9; i++) {
                        String bad = sides(bar, g[0] / 2 - 91 + 3 + 20 * i, g[1] - 19, gs, p);
                        if (bad != null) {
                            hotWrong++;
                            if (hotFirst == null) {
                                hotFirst = "slot " + i + ": " + bad;
                            }
                        }
                    }
                    c.note(preset + " hotbar: slots 2-8 (slot 1's left edge is under slot 0's selection ring), " + hotWrong + " with a missing side" + (hotFirst == null ? "" : "; first " + hotFirst));
                    c.check(hotWrong == 0, preset + ": " + hotWrong + " hotbar slot(s) lack a side: " + hotFirst);
                } else {
                    c.note("DEFAULT hotbar is vanilla's own (measured by 897)");
                }
            }
            c.check(totalSlots == 360, "measured " + totalSlots + " slots over the four presets, want 360");
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            if (saved != null) {
                Files.write(file, saved);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(ICFG, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1313 Health and Mana Bars a hair thinner by default
    // =================================================================================================================

    static void barsThinner(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Files.deleteIfExists(file);
            int[] fresh = c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                Object cfg = Mod.cfg(PS);
                Object health = Mod.enumValue("playerstats.StatElements$Readout", "HEALTH_BAR");
                Object mana = Mod.enumValue("playerstats.StatElements$Readout", "MANA_BAR");
                return new int[]{((Number) Mod.staticCall("playerstats.StatElements", "thickness", health)).intValue(),
                        ((Number) Mod.staticCall("playerstats.StatElements", "thickness", mana)).intValue(),
                        ((Number) Mod.call(cfg, "getBarHeight")).intValue(),
                        (Boolean) Mod.call(cfg, "isBarShowValue") ? 1 : 0,
                        ((Number) Mod.field("playerstats.StatElements", "MIN_VALUE_THICKNESS")).intValue()};
            });
            c.note("fresh config: health bar " + fresh[0] + " thick, mana " + fresh[1] + " (Bar Height " + fresh[2]
                    + ", Show Value " + (fresh[3] == 1) + ", floor " + fresh[4] + ")");
            c.check(fresh[3] == 1, "Show Value is not on by default - the floor would not be what a default bar draws at");
            c.check(fresh[0] == 12 && fresh[1] == 12, "a default bar is " + fresh[0] + "/" + fresh[1] + " thick, want 12 (was 14)");
            int[] chosen = c.onClient(mc -> {
                Object cfg = Mod.cfg(PS);
                Object health = Mod.enumValue("playerstats.StatElements$Readout", "HEALTH_BAR");
                Mod.call(cfg, "setBarHeight", 20);
                int h20 = ((Number) Mod.staticCall("playerstats.StatElements", "thickness", health)).intValue();
                Mod.call(cfg, "setBarHeight", 8);
                Mod.call(cfg, "setBarShowValue", false);
                int h8 = ((Number) Mod.staticCall("playerstats.StatElements", "thickness", health)).intValue();
                Mod.call(cfg, "setBarShowValue", true);
                return new int[]{h20, h8};
            });
            c.note("Bar Height 20 -> " + chosen[0] + " thick; Bar Height 8 with Show Value off -> " + chosen[1]);
            c.check(chosen[0] == 20 && chosen[1] == 8, "a chosen thickness is not drawn as set: " + chosen[0] + "/" + chosen[1]);
        } finally {
            if (saved != null) {
                Files.write(file, saved);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1314 Peek Chat
    // =================================================================================================================

    private static Object chat(Minecraft mc) {
        return Mod.staticCall("compat.McCompat", "chat", mc);
    }

    private static int scrollPos(UiCase c) {
        return c.onClient(mc -> (Integer) Mod.field(chat(mc), "chatScrollbarPos"));
    }

    private static boolean peekOpen(Minecraft mc) {
        Screen s = McCompat.screen(mc);
        return s != null && s.getClass().getName().endsWith(".peekchat.PeekChatScreen");
    }

    /** Pixels in the input bar strip (GUI y h-14..h-2) that differ from {@code base} by more than 30, from x 2 to the
     *  left of the hotbar: the held item on the right sways between two pictures (2,053 px of it in one run). */
    private static int barChanged(BufferedImage base, BufferedImage img, int gs, int w, int h) {
        int n = 0;
        for (int y = (h - 14) * gs; y < (h - 2) * gs; y++) {
            for (int x = 2 * gs; x < (w / 2 - 100) * gs; x++) {
                if (delta(rgb(base, x, y), rgb(img, x, y)) > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    static void peekChat(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(PEEK), "CONFIG_PATH"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        List<String> sent = new CopyOnWriteArrayList<>();
        try {
            closeScreen(c);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(PEEK);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setKeyCode", PEEK_KEY);
                Object chat = chat(mc);
                for (int i = 1; i <= 80; i++) {
                    Mod.call(chat, "addClientSystemMessage", Component.literal("§7peek history line " + i));
                }
                Mod.call(chat, "addClientSystemMessage", Component.literal("§bclick this link")
                        .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create("https://example.com/peek")))));
                return null;
            });
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd) {
                    sent.add("/" + cmd.command());
                } else if (p instanceof ServerboundChatPacket msg) {
                    sent.add(msg.message());
                }
            };
            c.ticks(3);
            int[] g = c.onClient(mc -> new int[]{mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight()});
            int gs = g[0];
            BufferedImage none = shot(c, "peek-chat-none");

            // ---- control: plain chat draws its input bar ----
            c.onClient(mc -> {
                McCompat.setScreen(mc, new ChatScreen("", false));
                return null;
            });
            c.ticks(3);
            BufferedImage plain = shot(c, "peek-chat-control-plain-chat");
            int plainBar = barChanged(none, plain, gs, g[1], g[2]);
            closeScreen(c);
            c.note("plain chat: " + plainBar + " px changed in the input bar strip (the probe sees a bar)");
            int area = 12 * gs * ((g[1] / 2 - 100) - 2) * gs;
            c.check(plainBar > area / 2, "the probe does not see plain chat's input bar (" + plainBar + " px) - it would prove nothing");

            // A half-typed message saved in the real chat (after the control: closing plain chat with an empty box
            // discards the draft, as vanilla does).
            c.onClient(mc -> {
                Mod.call(chat(mc), "saveAsDraft", "half typed message");
                return null;
            });
            // ---- the key opens Peek Chat ----
            press(c, PEEK_KEY);
            boolean opened = c.onClient(DocMiscCases::peekOpen);
            c.check(opened, "pressing the Peek Chat key did not open Peek Chat (screen " + c.onClient(mc ->
                    String.valueOf(McCompat.screen(mc))) + ")");
            String box = c.onClient(mc -> {
                EditBox in = (EditBox) Mod.field(McCompat.screen(mc), "input");
                return in.isVisible() + "/" + in.isFocused() + "/" + McCompat.screen(mc).getFocused();
            });
            c.note("input box visible/focused/screen focus: " + box);
            c.check(box.startsWith("false/false/null"), "Peek Chat has an input box that shows or takes keys: " + box);
            BufferedImage peek = shot(c, "peek-chat-open");
            int peekBar = barChanged(none, peek, gs, g[1], g[2]);
            c.note("Peek Chat: " + peekBar + " px changed in the input bar strip");
            // Grass shimmer between two pictures moves a few dozen pixels; a bar darkens most of the strip.
            c.check(peekBar < area / 50, "Peek Chat still draws an input bar: " + peekBar + " of " + area + " px changed (plain chat " + plainBar + ")");
            boolean expanded = c.onClient(mc -> (Boolean) Mod.call(chat(mc), "isChatFocused"));
            c.check(expanded, "the chat is not shown as open (isChatFocused false) behind Peek Chat");

            // ---- typing does nothing and sends nothing ----
            c.ctx().getInput().typeChars("hello");
            c.ctx().getInput().pressKey(257); // Enter
            c.ticks(3);
            String typed = c.onClient(mc -> peekOpen(mc) ? ((EditBox) Mod.field(McCompat.screen(mc), "input")).getValue() : "<closed>");
            c.note("typed 'hello' + Enter: input '" + typed + "', sent " + sent);
            c.check("".equals(typed), "typing reached Peek Chat's input: '" + typed + "'");
            c.check(sent.isEmpty(), "Peek Chat sent something: " + sent);

            // ---- scrolling: the mouse wheel over the chat, then Page Up, then Home ----
            int s0 = scrollPos(c);
            double[] over = c.onClient(mc -> {
                Window w = mc.getWindow();
                return new double[]{20.0 * w.getScreenWidth() / w.getGuiScaledWidth(),
                        (w.getGuiScaledHeight() - 60.0) * w.getScreenHeight() / w.getGuiScaledHeight()};
            });
            c.ctx().getInput().setCursorPos(over[0], over[1]);
            c.ticks(2);
            c.ctx().getInput().scroll(3.0);
            c.ticks(2);
            int s1 = scrollPos(c);
            c.ctx().getInput().pressKey(266); // Page Up
            c.ticks(2);
            int s2 = scrollPos(c);
            BufferedImage scrolled = shot(c, "peek-chat-scrolled");
            c.ctx().getInput().pressKey(268); // Home
            c.ticks(2);
            int s3 = scrollPos(c);
            c.note("scroll position: open " + s0 + ", after the wheel " + s1 + ", after Page Up " + s2 + ", after Home " + s3);
            c.check(s0 == 0 && s1 > 0 && s2 > s1 && s3 == 0, "Peek Chat did not scroll: " + s0 + " -> " + s1 + " -> " + s2
                    + " -> " + s3);
            c.check(c.onClient(DocMiscCases::peekOpen), "Peek Chat closed while scrolling");

            // ---- the link is clickable: found on the open picture by its aqua letters (where the chat sits depends on the
            // window), then clicked with the real mouse ----
            double scale = peek.getWidth() / (double) g[1];
            int[] aqua = null;
            for (int y = 0; y < peek.getHeight() && aqua == null; y++) {
                for (int x = 0; x < peek.getWidth() / 2; x++) {
                    if (rgb(peek, x, y) == 0x55FFFF) {
                        aqua = new int[]{x, y};
                        break;
                    }
                }
            }
            c.check(aqua != null, "no aqua link letters on the Peek Chat picture");
            String linkHit = null;
            for (int dy = 0; dy <= 6 && linkHit == null; dy += 3) {
                double gx = aqua[0] / scale + 3;
                double gy = aqua[1] / scale + dy;
                double[] at = c.onClient(mc -> {
                    Window w = mc.getWindow();
                    return new double[]{gx * w.getScreenWidth() / w.getGuiScaledWidth(),
                            gy * w.getScreenHeight() / w.getGuiScaledHeight()};
                });
                c.ctx().getInput().setCursorPos(at[0], at[1]);
                c.ticks(1);
                c.ctx().getInput().pressMouse(0);
                c.ticks(2);
                String now = c.onClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getSimpleName());
                if (!now.equals("PeekChatScreen")) {
                    linkHit = now + String.format(Locale.ROOT, " at GUI (%.0f, %.0f)", gx, gy);
                }
            }
            c.note("clicking the link line: " + linkHit);
            c.check(linkHit != null && linkHit.startsWith("ConfirmLinkScreen"), "clicking the link in Peek Chat did not open the "
                    + "link prompt: " + linkHit);
            closeScreen(c);

            // ---- Escape and the key close it; the draft is untouched ----
            press(c, PEEK_KEY);
            boolean again = c.onClient(DocMiscCases::peekOpen);
            c.ctx().getInput().pressKey(256); // Escape
            c.ticks(3);
            boolean afterEsc = c.onClient(DocMiscCases::peekOpen);
            press(c, PEEK_KEY);
            boolean third = c.onClient(DocMiscCases::peekOpen);
            press(c, PEEK_KEY);
            boolean afterKey = c.onClient(mc -> McCompat.screen(mc) != null);
            c.note("open " + again + ", after Escape " + afterEsc + "; open " + third + ", after the key " + afterKey);
            c.check(again && !afterEsc && third && !afterKey, "Escape / the Peek Chat key did not open and close it: "
                    + again + "/" + afterEsc + "/" + third + "/" + afterKey);
            String draft = c.onClient(mc -> {
                Object d = Mod.field(chat(mc), "latestDraft");
                return d == null ? null : (String) Mod.call(d, "text");
            });
            c.note("draft after Peek Chat: " + draft);
            c.check("half typed message".equals(draft), "Peek Chat lost or changed the saved chat draft: " + draft);
            c.check(sent.isEmpty(), "something was sent during the case: " + sent);
            c.check(scrolled != null, "no scrolled picture");
        } finally {
            PacketTrace.tap = null;
            closeScreen(c);
            c.onClient(mc -> {
                Mod.call(chat(mc), "discardDraft");
                return null;
            });
            if (saved != null) {
                Files.write(file, saved);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(PEEK, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 1315 Open Loadouts button
    // =================================================================================================================

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> paneChildren(Screen s) {
        AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
        return pane == null ? List.of() : new ArrayList<>((Collection<AbstractWidget>) R.get(pane, "children"));
    }

    static void loadoutsButton(UiCase c) throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        try {
            Screen s = DungeonB2Cases.openTab(c, "Loadout Keybinds");
            double[] box = c.onClient(mc -> {
                try {
                    R.call0(s, "rebuild");
                } catch (Throwable t) {
                    throw new AssertionError(UiCase.describe(t), t);
                }
                for (AbstractWidget w : paneChildren(s)) {
                    if (ModScreenDriver.label(w).equals("Open Loadouts")) {
                        return new double[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                    }
                }
                return null;
            });
            c.check(box != null, "no 'Open Loadouts' button in the Loadout Keybinds tab: " + DungeonB2Cases.labels(c, s));
            String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Loadout Keybinds",
                    "Open Loadouts"));
            c.note("tooltip: " + tip);
            c.check(tip != null && tip.contains("/loadouts"), "the Open Loadouts button has no tooltip naming /loadouts: " + tip);
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd) {
                    sent.add(cmd.command());
                }
            };
            double[] at = c.onClient(mc -> {
                float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
                Window w = mc.getWindow();
                double sx = box[0] + box[2] / 2;
                double sy = box[1] + box[3] / 2;
                return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                        sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ticks(2);
            c.ctx().getInput().pressMouse(0);
            c.ticks(5);
            c.note("commands sent by the press: " + sent);
            c.check(sent.contains("loadouts"), "pressing Open Loadouts did not send /loadouts: " + sent);
            c.check(sent.size() == 1, "the press sent " + sent.size() + " commands, want exactly one: " + sent);
        } finally {
            PacketTrace.tap = null;
            closeScreen(c);
        }
    }
}
