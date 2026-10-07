package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 236-menu-partyfinder-style: what the Party Finder Overlay draws on a real Party Finder menu matches what its
 * settings-tab style preview draws, for every "Party Finder style".
 *
 * <p>killer560, 2026-10-07: "the dungeon party finder overlay is broken too and does not show their style like the
 * preview says it will." The preview always has stats (fixed sample data); the real tooltip only styled a member line
 * once the stats service had answered for that player, and on Hypixel that service answers {@code {"result":{}}}
 * for every name - so no real line was ever styled. This client runs offline, which is the same as that: no stats.
 *
 * <p>The menu has four parties: slot 10 is the preview's own roster (killer560, aut0balls, agreencatgirl,
 * femboy_recruiter, latinomommy as Tank/Healer/Archer/Berserk/Mage 50/45/40/35/30, Master Mode floor 7, the names in
 * the preview's rank colours), 11 needs Catacombs 50, 12 needs the previous floor, 13 is a joinable floor 5 party.
 * For each of None / Style 1 / Style 2 / Custom (Style 1 as a custom string) / Style 1 with Rank Name Colors:
 * <ol>
 *   <li>no stats: every member line of the REAL tooltip ({@code getTooltipFromContainerItem}, through the mod's
 *       mixin) is in the chosen style with {@code ?} for the stats, killer560's exactly; and the drawn tooltip has
 *       the style's dark-grey brackets in it (pixels of {@code 0x555555} that the plain tooltip does not have);</li>
 *   <li>with the preview's own stats put in the mod's stats cache: the real tooltip's member lines are, colour for
 *       colour and character for character, the lines the preview widget draws ({@code renderPreviewLines}).</li>
 * </ol>
 * Then the highlight, by pixels against a highlight-off frame: green only on 10 and 13, red only on 11 and 12.
 * Every style is run and reported before the case fails, so an old jar shows which ones are broken.
 */
final class PartyFinderStyleCases {

    private PartyFinderStyleCases() {
    }

    static final String CFG = "partyfinder.PartyFinderOverlayConfig";
    static final String CUSTOM_STYLE1 = "&8[$RoleColor$RoleSingle&8] $NameColor$Name &8[&e$RoleLevel &7| &6$Cata&8] "
            + "&8[&3$SecretsShort &7| &b$SecretShortAvg&8] &8[$PB&8]";
    static final String[] NAMES = {"killer560", "aut0balls", "agreencatgirl", "femboy_recruiter", "latinomommy"};

    /** killer560's line (Tank 50) with no stats, as plain text, per variant. */
    record Variant(String label, String mode, boolean rankColors, Pattern noStatsKiller) {
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("None", "NONE", false,
                    Pattern.compile("^ killer560: Tank \\(50\\) \\(\\?\\) \\[\\? \\| \\?\\] \\[\\?\\]$")),
            new Variant("Style 1", "STYLE1", false,
                    Pattern.compile("^\\[T\\] killer560 \\[50 \\| \\?\\] \\[\\? \\| \\?\\] \\[\\?\\]$")),
            new Variant("Style 2", "STYLE2", false,
                    Pattern.compile("^\\[T 50\\] killer560 \\[\\? \\| \\? \\| \\?\\] \\?$")),
            new Variant("Custom", "CUSTOM", false,
                    Pattern.compile("^\\[T\\] killer560 \\[50 \\| \\?\\] \\[\\? \\| \\?\\] \\[\\?\\]$")),
            new Variant("Style 1 + Rank Name Colors", "STYLE1", true,
                    Pattern.compile("^\\[T\\] killer560 \\[50 \\| \\?\\] \\[\\? \\| \\?\\] \\[\\?\\]$")));

    static JsonObject head(String name, List<String> lore) {
        JsonObject o = new JsonObject();
        o.addProperty("item", "minecraft:player_head");
        o.addProperty("name", name);
        JsonArray a = new JsonArray();
        lore.forEach(a::add);
        o.add("lore", a);
        return o;
    }

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        slots.add("10", head("§bkiller560's Party", List.of(
                "§7Dungeon: §bMaster Mode The Catacombs", "§7Floor: §bFloor VII", "",
                "§7Members:",
                " §6killer560§f: §eTank §b(§e50§b)",
                " §baut0balls§f: §eHealer §b(§e45§b)",
                " §aagreencatgirl§f: §eArcher §b(§e40§b)",
                " §7femboy_recruiter§f: §eBerserk §b(§e35§b)",
                " §blatinomommy§f: §eMage §b(§e30§b)",
                "", "§eClick to join!")));
        slots.add("11", head("§aAlice's Party", List.of(
                "§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "",
                " §aAlice§f: §eMage §b(§e50§b)", " §aCarol§f: §eArcher §b(§e48§b)",
                "", "§cRequires Catacombs Level 50!")));
        slots.add("12", head("§aDan's Party", List.of(
                "§7Dungeon: §bMaster Mode The Catacombs", "§7Floor: §bFloor VII", "",
                " §aDan§f: §eTank §b(§e44§b)", " §aErin§f: §eHealer §b(§e41§b)", " §aFrank§f: §eBerserk §b(§e39§b)",
                "", "§cComplete previous floor first!")));
        slots.add("13", head("§aGus's Party", List.of(
                "§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor V", "",
                " §aGus§f: §eArcher §b(§e30§b)",
                "", "§eClick to join!")));
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Party Finder");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        return spec;
    }

    static void style(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> failures = new ArrayList<>();
        Map<String, Object> cache = null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true).set(CFG, "Highlight", true)
                    .set(CFG, "MemberCount", true).set(CFG, "ShowMissing", true).set(CFG, "RankNameColors", false)
                    .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "BOTH"))
                    .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "NONE"))
                    .set(CFG, "CustomStyle", CUSTOM_STYLE1);
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            c.waitUntil("PartyFinderOverlay to parse the parties in slots 10-13", mc -> {
                Object[] p = (Object[]) Mod.field("partyfinder.PartyFinderOverlay", "parties");
                return p[10] != null && p[11] != null && p[12] != null && p[13] != null;
            }, 100);
            Object party10 = c.onClient(mc -> ((Object[]) Mod.field("partyfinder.PartyFinderOverlay", "parties"))[10]);
            c.note("slot 10 parsed as " + party10);
            c.check(((List<?>) Mod.call(party10, "members")).size() == 5, "slot 10 should parse 5 members: " + party10);

            @SuppressWarnings("unchecked")
            Map<String, Object> statsCache = (Map<String, Object>) c.onClient(
                    mc -> Mod.field("partyfinder.PartyFinderStatsApi", "CACHE"));
            cache = statsCache;
            for (String n : NAMES) {
                cache.remove(n);
            }

            // The plain tooltip (Tooltip Stats off) at the hover point, for the pixel diffs.
            double[] hover10 = slotCentre(c, 10);
            BufferedImage plainTip;
            try (AutoCloseable off = c.onClient(mc -> Mod.with(CFG, "Tooltip", false))) {
                plainTip = shot(c, "plain-tooltip", hover10);
            }

            for (Variant v : VARIANTS) {
                try (AutoCloseable m = c.onClient(mc -> Mod.with(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", v.mode())));
                     AutoCloseable r = c.onClient(mc -> Mod.with(CFG, "RankNameColors", v.rankColors()))) {
                    for (String n : NAMES) {
                        cache.remove(n);
                    }
                    // 1. No stats - what Hypixel gives him today.
                    List<Component> tip = tooltip(c, 10);
                    List<Component> members = memberLines(tip);
                    List<String> plain = new ArrayList<>();
                    members.forEach(l -> plain.add(strip(l.getString())));
                    boolean allStyled = members.size() == 5;
                    for (String p : plain) {
                        if (!p.contains("?")) {
                            allStyled = false;
                        }
                    }
                    String killer = plain.isEmpty() ? "(none)" : plain.get(0);
                    boolean killerExact = v.noStatsKiller().matcher(killer).matches();
                    BufferedImage styledTip = shot(c, "tooltip-" + v.mode() + (v.rankColors() ? "-rank" : ""), hover10);
                    int darkGrey = countNew(plainTip, styledTip, 0x555555);
                    String noStats = (allStyled && killerExact && darkGrey > 0) ? "PASS" : "FAIL";
                    System.out.println("[236-menu-partyfinder-style] " + v.label() + " no-stats: " + noStats
                            + " (member lines " + members.size() + ", all with '?' " + allStyled + ", killer560 line '"
                            + killer + "', new 0x555555 tooltip pixels " + darkGrey + ")");
                    c.note(v.label() + " no stats: " + noStats + " - killer560 line '" + killer + "', "
                            + darkGrey + " dark-grey bracket pixels drawn");
                    if (!noStats.equals("PASS")) {
                        failures.add(v.label() + " with no stats: real tooltip " + plain);
                    }

                    // 2. The preview's own stats: the real tooltip must BE the preview.
                    c.ctx().runOnClient(mc -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cc = (Map<String, Object>) Mod.field("partyfinder.PartyFinderStatsApi", "CACHE");
                        for (Object p : (List<?>) Mod.field("partyfinder.PartyFinderOverlay", "PREVIEW_PLAYERS")) {
                            cc.put(((String) Mod.call(p, "name")).toLowerCase(java.util.Locale.ROOT), Mod.call(p, "stats"));
                        }
                    });
                    List<Component> real = memberLines(tooltip(c, 10));
                    @SuppressWarnings("unchecked")
                    List<Component> preview = c.onClient(mc -> List.copyOf((List<Component>) Mod.staticCall(
                            "partyfinder.PartyFinderOverlay", "renderPreviewLines", Mod.cfg(CFG))));
                    List<String> realRuns = new ArrayList<>();
                    List<String> previewRuns = new ArrayList<>();
                    real.forEach(l -> realRuns.add(runs(l)));
                    preview.forEach(l -> previewRuns.add(runs(l)));
                    boolean same = realRuns.equals(previewRuns);
                    System.out.println("[236-menu-partyfinder-style] " + v.label() + " overlay==preview: "
                            + (same ? "PASS" : "FAIL") + " (" + real.size() + " real vs " + preview.size() + " preview lines)");
                    for (int i = 0; i < Math.max(realRuns.size(), previewRuns.size()); i++) {
                        String a = i < realRuns.size() ? realRuns.get(i) : "(missing)";
                        String b = i < previewRuns.size() ? previewRuns.get(i) : "(missing)";
                        if (!a.equals(b)) {
                            System.out.println("[236-menu-partyfinder-style]   line " + i + " real    " + a);
                            System.out.println("[236-menu-partyfinder-style]   line " + i + " preview " + b);
                        }
                    }
                    c.note(v.label() + " overlay == preview: " + same + (real.isEmpty() ? "" : " - '" + strip(real.get(0).getString()) + "'"));
                    if (!same) {
                        failures.add(v.label() + " with the preview's stats: real tooltip differs from the preview");
                    }
                    for (String n : NAMES) {
                        cache.remove(n);
                    }
                }
            }

            // 3. Highlight colours on the right slots.
            parkCursor(c);
            BufferedImage noHighlight;
            try (AutoCloseable off = c.onClient(mc -> Mod.with(CFG, "Highlight", false))) {
                noHighlight = shot(c, "highlight-off", null);
            }
            BufferedImage highlight = shot(c, "highlight-on", null);
            int[][] expect = {{10, 1}, {11, 0}, {12, 0}, {13, 1}}; // 1 = joinable (green)
            for (int[] e : expect) {
                int[] box = slotBox(c, e[0]);
                int[] gr = greenRed(noHighlight, highlight, box);
                boolean ok = e[1] == 1 ? gr[0] > 20 && gr[1] == 0 : gr[1] > 20 && gr[0] == 0;
                System.out.println("[236-menu-partyfinder-style] highlight slot " + e[0] + " (" + (e[1] == 1 ? "joinable" : "blocked")
                        + "): " + (ok ? "PASS" : "FAIL") + " (green " + gr[0] + ", red " + gr[1] + ")");
                c.note("highlight slot " + e[0] + ": green " + gr[0] + ", red " + gr[1]);
                if (!ok) {
                    failures.add("highlight slot " + e[0] + " green " + gr[0] + " red " + gr[1]);
                }
            }
            // And the stats lines: the tooltip carries the Missing line for a party with gaps.
            List<String> tip13 = new ArrayList<>();
            tooltip(c, 13).forEach(l -> tip13.add(strip(l.getString())));
            boolean missing = tip13.stream().anyMatch(s -> s.startsWith("Missing: ") && s.contains("Mage"));
            System.out.println("[236-menu-partyfinder-style] slot 13 Missing line: " + (missing ? "PASS" : "FAIL") + " " + tip13);
            if (!missing) {
                failures.add("slot 13 tooltip has no Missing line: " + tip13);
            }
        } finally {
            if (cache != null) {
                for (String n : NAMES) {
                    cache.remove(n);
                }
            }
            parkCursor(c);
            MenuKit.reset(c);
        }
        System.out.println("[236-menu-partyfinder-style] " + (failures.isEmpty() ? "PASS" : "FAIL " + failures));
        c.check(failures.isEmpty(), "Party Finder overlay vs preview: " + failures);
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Component> tooltip(Session c, int slot) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            ItemStack st = s.getMenu().getSlot(slot).getItem();
            return List.copyOf((List<Component>) Mod.call(s, "getTooltipFromContainerItem", st));
        });
    }

    /** The tooltip lines naming one of the five roster players, in order. */
    static List<Component> memberLines(List<Component> tip) {
        List<Component> out = new ArrayList<>();
        for (Component l : tip) {
            String s = l.getString();
            for (String n : NAMES) {
                if (s.contains(n) && !s.contains("'s Party")) {
                    out.add(l);
                    break;
                }
            }
        }
        return out;
    }

    static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    static final int[] LEGACY_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /**
     * The line as it is DRAWN: each visible character with the colour it renders in, whether the colour came from a
     * style or from an inline section-sign code. Whitespace carries no colour (it draws nothing). Bold/italic are not
     * compared.
     */
    static String runs(Component line) {
        StringBuilder out = new StringBuilder();
        int[] last = {-2};
        line.visit((style, text) -> {
            int colour = style.getColor() == null ? -1 : style.getColor().getValue();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '§' && i + 1 < text.length()) {
                    int idx = "0123456789abcdef".indexOf(Character.toLowerCase(text.charAt(i + 1)));
                    if (idx >= 0) {
                        colour = LEGACY_RGB[idx];
                    } else if (Character.toLowerCase(text.charAt(i + 1)) == 'r') {
                        colour = -1;
                    }
                    i++;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    out.append(ch);
                    continue;
                }
                if (colour != last[0]) {
                    out.append(colour < 0 ? "{-}" : String.format("{%06X}", colour));
                    last[0] = colour;
                }
                out.append(ch);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    // ---- pixels -------------------------------------------------------------------------------------------------

    static double[] slotCentre(Session c, int slot) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(slot);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(left + sl.x + 8) * scale, (top + sl.y + 8) * scale};
        });
    }

    /** {x0, y0, x1, y1} in screenshot pixels for a slot's 16x16 square. */
    static int[] slotBox(Session c, int slot) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot sl = s.getMenu().slots.get(slot);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            double scale = mc.getWindow().getGuiScale();
            return new int[]{(int) Math.round((left + sl.x) * scale), (int) Math.round((top + sl.y) * scale),
                    (int) Math.round((left + sl.x + 16) * scale), (int) Math.round((top + sl.y + 16) * scale)};
        });
    }

    static void parkCursor(Session c) {
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(2);
    }

    static BufferedImage shot(Session c, String label, double[] cursor) {
        if (cursor != null) {
            c.ctx().getInput().setCursorPos(cursor[0], cursor[1]);
        }
        c.ctx().waitTicks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    /** Pixels that are exactly {@code rgb} in {@code now} and were not in {@code base}. */
    static int countNew(BufferedImage base, BufferedImage now, int rgb) {
        int n = 0;
        int w = Math.min(base.getWidth(), now.getWidth());
        int h = Math.min(base.getHeight(), now.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = now.getRGB(x, y) & 0xFFFFFF;
                if (a == rgb && (base.getRGB(x, y) & 0xFFFFFF) != rgb) {
                    n++;
                }
            }
        }
        return n;
    }

    /** {green, red} pixels in {@code box} that changed from {@code base}. */
    static int[] greenRed(BufferedImage base, BufferedImage now, int[] box) {
        int green = 0;
        int red = 0;
        for (int y = Math.max(0, box[1]); y < Math.min(now.getHeight(), box[3]); y++) {
            for (int x = Math.max(0, box[0]); x < Math.min(now.getWidth(), box[2]); x++) {
                int rgb = now.getRGB(x, y);
                if (rgb == base.getRGB(x, y)) {
                    continue;
                }
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (g > 220 && r < 40 && b < 40) {
                    green++;
                } else if (r > 220 && g < 40 && b < 40) {
                    red++;
                }
            }
        }
        return new int[]{green, red};
    }
}
