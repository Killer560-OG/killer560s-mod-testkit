package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * killer560's 2026-10-15 custom-names list (mod names-profiles-1015):
 * <ul>
 *   <li>1550 a name inside a command is never restyled: "/&lt;own ign&gt;", a command with the name as an argument, and the
 *       same through a Component's visual order and the width path all come back untouched, while ordinary text with the
 *       name is replaced (the control that the feature acted); the command-suggestions mixin is applied.</li>
 *   <li>1551 the rank tag: Keep Original leaves "[MVP++] name", None removes it, a Hypixel rank or a custom one replaces
 *       it (text, bracket colour, plus colour), through a String and through Hypixel's multi-part Component, and a tag that
 *       is not directly in front of the name or is not a rank ("[285]") is left alone; the Name Changer tab's Rank row
 *       cycles it and shows the custom text box; it survives a reload.</li>
 *   <li>1552 names shared by every profile: with Per-Profile Names off, loading a profile does not change the names and a
 *       profile folder carries none; with it on, each profile restores its own and the switch stays on across loads; a
 *       config from before this change migrates into the shared file.</li>
 * </ul>
 */
final class NamesProfilesCases {

    private NamesProfilesCases() {
    }

    private static final String NC = "namechanger.NameChangerConfig";
    private static final String NR = "namechanger.NameReplacer";
    private static final String PM = "profiles.ProfileManager";
    private static final String PRESET = "namechanger.NameRank$Preset";

    private static final int RED = 0xFFFF5555;
    private static final int BLUE = 0xFF5555FF;
    private static final int GOLD = 0xFFFFAA00;

    /** A config path of the mod; the shared names file's does not exist on a jar from before this change (then null). */
    private static Path path(String field) {
        try {
            return (Path) Mod.field(NC, field);
        } catch (AssertionError e) {
            if (field.equals("NAMES_PATH")) {
                return null;
            }
            throw e;
        }
    }

    private static byte[] snapshot(Path p) throws Exception {
        return p != null && Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restore(Path p, byte[] bytes) throws Exception {
        if (p == null) {
            return;
        }
        if (bytes == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, bytes);
        }
    }

    private static String own(Minecraft mc) {
        return mc.getUser().getName();
    }

    /** The text a sequence paints, and the colour (0xRRGGBB, -1 none) of each glyph. */
    private record Flat(String text, List<Integer> colours) {
    }

    private static Flat flat(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        List<Integer> colours = new ArrayList<>();
        seq.accept((pos, style, cp) -> {
            sb.appendCodePoint(cp);
            colours.add(style.getColor() == null ? -1 : style.getColor().getValue());
            return true;
        });
        return new Flat(sb.toString(), colours);
    }

    private static Flat replaceSeq(Component component) {
        FormattedCharSequence out = (FormattedCharSequence) Mod.staticCall(NR, "replace", component.getVisualOrderText());
        return flat(out);
    }

    private static String replaceStr(String s) {
        return NameColorPlain.of((String) Mod.staticCall(NR, "replace", s));
    }

    /** NameColor.plain through the mod: the letters a player sees. */
    private static final class NameColorPlain {
        static String of(String s) {
            return (String) Mod.staticCall("namechanger.NameColor", "plain", s);
        }
    }

    private static void baseConfig(Minecraft mc, Object cfg) {
        Mod.call(cfg, "setEnabled", true);
        Mod.call(cfg, "setOwnNameEnabled", true);
        Mod.call(cfg, "setOwnDisplayName", "Zed");
        Mod.call(cfg, "setOwnColor", RED);
        Mod.call(cfg, "setOwnColorFadeEnabled", false);
        Mod.call(cfg, "setOwnBold", false);
        Mod.call(cfg, "clearOwnCharColors");
        if (Mod.has("namechanger.NameRank")) { // a jar from before the rank has neither the class nor the setters
            Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "DEFAULT"));
            Mod.call(cfg, "setOwnRankColor", 0);
            Mod.call(cfg, "setOwnRankPlusColor", 0);
            Mod.call(cfg, "setOwnRankCustomText", "");
        }
        Mod.call(cfg, "setRandomizeOthers", false);
    }

    // ======================================================================================================
    // 1550 commands are never restyled
    // ======================================================================================================

    static void commandNames(UiCase c) throws Exception {
        Path main = path("CONFIG_PATH");
        Path names = path("NAMES_PATH");
        byte[] mainBefore = snapshot(main);
        byte[] namesBefore = snapshot(names);
        try {
            String why = c.onClient(mc -> {
                String real = own(mc);
                Object cfg = Mod.cfg(NC);
                baseConfig(mc, cfg);
                Mod.call(cfg, "save");
                // The control: ordinary text with the name IS replaced, so the feature acted.
                String shown = replaceStr("hello " + real + " there");
                if (!shown.equals("hello Zed there")) {
                    return "control failed: 'hello " + real + " there' painted as '" + shown + "'";
                }
                String[] commands = {"/" + real, "/" + real + " now", "/p invite " + real, "  /msg " + real + " hi",
                        "/killer560 " + real};
                for (String cmd : commands) {
                    String out = replaceStr(cmd);
                    if (!out.equals(cmd)) {
                        return "the command '" + cmd + "' was restyled into '" + out + "'";
                    }
                    Flat f = replaceSeq(Component.literal(cmd));
                    if (!f.text().equals(cmd)) {
                        return "the command '" + cmd + "' through a sequence was restyled into '" + f.text() + "'";
                    }
                    Object width = Mod.staticCall(NR, "replaceForWidth", Component.literal(cmd));
                    if (width != null) {
                        return "the width of the command '" + cmd + "' was measured on a replaced name";
                    }
                }
                // A coloured Hypixel-style line (a § code first) with a slash behind it is a command too.
                Flat styled = replaceSeq(Component.literal("/").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(real).withStyle(ChatFormatting.GRAY)));
                if (!styled.text().equals("/" + real)) {
                    return "a styled '/" + real + "' became '" + styled.text() + "'";
                }
                // And a name that merely follows a slash later in a chat line is still replaced (not a command line).
                String chat = replaceStr(real + ": use /help");
                if (!chat.equals("Zed: use /help")) {
                    return "an ordinary chat line became '" + chat + "'";
                }
                // The suggestions list and usage hint are drawn under the same suppression as an edit box.
                boolean applied = false;
                for (var m : net.minecraft.client.gui.components.CommandSuggestions.class.getDeclaredMethods()) {
                    applied |= m.getName().contains("commandSuggestionsStart");
                }
                if (!applied) {
                    return "the command-suggestions mixin was never applied to CommandSuggestions";
                }
                // Suppression itself: nothing is restyled between a push and its pop.
                Mod.staticCall(NR, "pushSuppress");
                String inside = replaceStr("hello " + real);
                Mod.staticCall(NR, "popSuppress");
                if (!inside.equals("hello " + real)) {
                    return "text under suppression was restyled into '" + inside + "'";
                }
                c.note("'" + real + "' in " + commands.length + " command shapes (String, sequence, width) stays as typed; "
                        + "the same name in chat becomes Zed; the CommandSuggestions mixin is applied");
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(main, mainBefore);
            restore(names, namesBefore);
            c.onClient(mc -> {
                Mod.staticCall(NC, "load");
                return null;
            });
        }
    }

    // ======================================================================================================
    // 1551 the rank tag
    // ======================================================================================================

    private static Component hypixelTag(String realName, ChatFormatting main, ChatFormatting plus) {
        MutableComponent tag = Component.literal("[MVP").withStyle(main);
        tag.append(Component.literal("++").withStyle(plus));
        tag.append(Component.literal("] ").withStyle(main));
        tag.append(Component.literal(realName).withStyle(main));
        tag.append(Component.literal(": hi").withStyle(ChatFormatting.WHITE));
        return tag;
    }

    static void rank(UiCase c) throws Exception {
        Path main = path("CONFIG_PATH");
        Path names = path("NAMES_PATH");
        byte[] mainBefore = snapshot(main);
        byte[] namesBefore = snapshot(names);
        try {
            String why = c.onClient(mc -> {
                String real = own(mc);
                Object cfg = Mod.cfg(NC);
                baseConfig(mc, cfg);
                Mod.call(cfg, "save");
                String line = "[MVP++] " + real + ": hi";

                // Keep Original: the tag is left alone, the name is still replaced.
                String keep = replaceStr(line);
                if (!keep.equals("[MVP++] Zed: hi")) {
                    return "Keep Original painted '" + keep + "'";
                }
                // None: the tag goes.
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "NONE"));
                Mod.call(cfg, "save");
                String none = replaceStr(line);
                if (!none.equals("Zed: hi")) {
                    return "None painted '" + none + "'";
                }
                // A Hypixel rank replaces whatever the line had.
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "VIP_PLUS"));
                Mod.call(cfg, "save");
                Flat vip = replaceSeq(hypixelTag(real, ChatFormatting.GOLD, ChatFormatting.RED));
                if (!vip.text().equals("[VIP+] Zed: hi")) {
                    return "VIP+ over Hypixel's multi-part MVP++ line painted '" + vip.text() + "'";
                }
                if (vip.colours().get(0) != 0x55FF55 || vip.colours().get(4) != 0xFFAA00) {
                    return "VIP+ colours were " + vip.colours().subList(0, 6) + " (want green bracket, gold plus)";
                }
                // Rank colour: a quick pick over the preset's own.
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "MVP_PLUS_PLUS"));
                Mod.call(cfg, "setOwnRankColor", BLUE);
                Mod.call(cfg, "save");
                Flat mvp = replaceSeq(hypixelTag(real, ChatFormatting.GREEN, ChatFormatting.GREEN));
                if (!mvp.text().equals("[MVP++] Zed: hi") || mvp.colours().get(0) != 0x5555FF) {
                    return "MVP++ in blue painted '" + mvp.text() + "' with '[' colour " + Integer.toHexString(mvp.colours().get(0));
                }
                if (mvp.colours().get(4) != 0xFF5555) {
                    return "MVP++'s plus lost its own red: " + Integer.toHexString(mvp.colours().get(4));
                }
                // A custom rank: text, colour, and a plus that follows it unless picked.
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "CUSTOM"));
                Mod.call(cfg, "setOwnRankCustomText", "KING++");
                Mod.call(cfg, "setOwnRankColor", BLUE);
                Mod.call(cfg, "setOwnRankPlusColor", 0);
                Mod.call(cfg, "save");
                Flat king = replaceSeq(hypixelTag(real, ChatFormatting.GOLD, ChatFormatting.RED));
                if (!king.text().equals("[KING++] Zed: hi")) {
                    return "a custom KING++ painted '" + king.text() + "'";
                }
                if (king.colours().get(0) != 0x5555FF || king.colours().get(5) != 0x5555FF) {
                    return "custom rank colours were " + king.colours().subList(0, 8);
                }
                Mod.call(cfg, "setOwnRankPlusColor", RED);
                Mod.call(cfg, "save");
                Flat kingRed = replaceSeq(hypixelTag(real, ChatFormatting.GOLD, ChatFormatting.RED));
                if (kingRed.colours().get(5) != 0xFF5555 || kingRed.colours().get(1) != 0x5555FF) {
                    return "a picked plus colour was not used: " + kingRed.colours().subList(0, 8);
                }
                // The String path: a Skyblock level before the rank stays; the rank is swapped.
                String level = replaceStr("[285] [MVP+] " + real + ": hi");
                if (!level.equals("[285] [KING++] Zed: hi")) {
                    return "a Skyblock level before the tag painted '" + level + "'";
                }
                // Not directly in front of the name, or not a rank: nothing is taken.
                String apart = replaceStr("[MVP++] and " + real);
                if (!apart.equals("[MVP++] and Zed")) {
                    return "a tag that is not in front of the name painted '" + apart + "'";
                }
                String levelOnly = replaceStr("[285] " + real);
                if (!levelOnly.equals("[285] Zed")) {
                    return "a Skyblock level was taken for a rank: '" + levelOnly + "'";
                }
                String plain = replaceStr("welcome " + real);
                if (!plain.equals("welcome Zed")) {
                    return "a tag was invented where there was none: '" + plain + "'";
                }
                // A rank with no display name typed still swaps the tag and leaves the name as it is.
                Mod.call(cfg, "setOwnDisplayName", "");
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "NONE"));
                Mod.call(cfg, "save");
                String bare = replaceStr(line);
                if (!bare.equals(real + ": hi")) {
                    return "None without a display name painted '" + bare + "'";
                }
                // Persistence.
                Mod.call(cfg, "setOwnDisplayName", "Zed");
                Mod.call(cfg, "setOwnRank", Mod.enumValue(PRESET, "CUSTOM"));
                Mod.call(cfg, "setOwnRankCustomText", "KING++");
                Mod.call(cfg, "save");
                Mod.staticCall(NC, "load");
                Object again = Mod.cfg(NC);
                if (!"CUSTOM".equals(((Enum<?>) Mod.call(again, "getOwnRank")).name())
                        || !"KING++".equals(Mod.call(again, "getOwnRankCustomText"))
                        || (Integer) Mod.call(again, "getOwnRankColor") != BLUE
                        || (Integer) Mod.call(again, "getOwnRankPlusColor") != RED) {
                    return "the rank did not survive a reload";
                }
                c.note("rank: Keep Original / None / VIP+ / MVP++ in blue / custom KING++ painted as expected through "
                        + "String and multi-part Component; levels and distant tags left alone; survives a reload");
                return null;
            });
            c.check(why == null, why);

            // The tab: the Rank row cycles, Custom shows the text box, the colour row steps.
            Screen[] s = new Screen[1];
            String ui = c.onClient(mc -> {
                Object cfg = Mod.cfg(NC);
                baseConfig(mc, cfg);
                Mod.call(cfg, "save");
                try {
                    s[0] = MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.call(s[0], "openFeature", MenuACases.leaf("CosmeticsTab"));
                if (row(s[0], "Rank:") == null) {
                    // the Custom Names section (the Name Changer inside Cosmetics) starts closed
                    AbstractWidget header = row(s[0], "▶ Custom Names");
                    if (header == null) {
                        return "no Custom Names section in Cosmetics: " + labels(s[0]);
                    }
                    ModScreenDriver.press(header);
                    Mod.call(s[0], "refresh");
                }
                AbstractWidget rankRow = row(s[0], "Rank:");
                if (rankRow == null) {
                    return "no Rank row in Cosmetics' Custom Names: " + labels(s[0]);
                }
                // Removed from the tab on purpose (mod 89a8e7fe, 2026-10-18); its saved value still loads.
                if (row(s[0], "Per-Profile Names") != null) {
                    return "the Per-Profile Names row is back in Custom Names (removed 2026-10-18)";
                }
                for (int i = 0; i < 8 && !"CUSTOM".equals(((Enum<?>) Mod.call(Mod.cfg(NC), "getOwnRank")).name()); i++) {
                    ModScreenDriver.press(row(s[0], "Rank:"));
                    Mod.call(s[0], "refresh");
                }
                if (!"CUSTOM".equals(((Enum<?>) Mod.call(Mod.cfg(NC), "getOwnRank")).name())) {
                    return "pressing the Rank row did not reach Custom";
                }
                AbstractWidget box = row(s[0], "Rank text");
                if (!(box instanceof EditBox eb)) {
                    return "Custom shows no rank text box: " + labels(s[0]);
                }
                eb.setValue("CHAMP");
                if (!"CHAMP".equals(Mod.call(Mod.cfg(NC), "getOwnRankCustomText"))) {
                    return "typing in the rank box did not reach the config";
                }
                AbstractWidget colour = row(s[0], "Rank Colour");
                if (colour == null) {
                    return "no Rank Color row for a custom rank: " + labels(s[0]);
                }
                ModScreenDriver.press(colour);
                Mod.call(s[0], "refresh");
                int picked = (Integer) Mod.call(Mod.cfg(NC), "getOwnRankColor");
                if (picked == 0) {
                    return "pressing Rank Color left the colour on 'the rank's own'";
                }
                c.note("the tab's Rank row cycled to Custom, the text box took 'CHAMP', Rank Color picked "
                        + Integer.toHexString(picked));
                return null;
            });
            c.check(ui == null, ui);
            c.ticks(2);
            MenuACases.shot(c, s[0], "names-rank-tab", null);
        } finally {
            restore(main, mainBefore);
            restore(names, namesBefore);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(NC, "load");
                return null;
            });
        }
    }

    private static AbstractWidget row(Screen s, String prefix) {
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static List<String> labels(Screen s) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    // ======================================================================================================
    // 1552 names shared by every profile
    // ======================================================================================================

    private static String name(Object cfg) {
        return (String) Mod.call(cfg, "getOwnDisplayName");
    }

    private static Path profileDir(String profile) {
        return ((Path) R.getStatic(R.cls(PM), "PROFILES_DIR")).resolve(profile);
    }

    static void sharedNames(UiCase c) throws Exception {
        Path main = path("CONFIG_PATH");
        Path names = path("NAMES_PATH");
        byte[] mainBefore = snapshot(main);
        byte[] namesBefore = snapshot(names);
        Path markerPath = (Path) R.getStatic(R.cls(PM), "ACTIVE_MARKER");
        String activeBefore = Files.exists(markerPath) ? Files.readString(markerPath, StandardCharsets.UTF_8).trim() : null;
        String[] made = {"tk-1552-p1", "tk-1552-p2", "tk-1552-p3", "tk-1552-p4"};
        try {
            String why = c.onClient(mc -> {
                try {
                    Object cfg = Mod.cfg(NC);
                    baseConfig(mc, cfg);
                    Mod.call(cfg, "setPerProfileNames", false);
                    Mod.call(cfg, "setOwnDisplayName", "ShareA");
                    Mod.call(cfg, "save");
                    Mod.staticCall(PM, "saveCurrentAsProfile", made[0]);
                    String shared = Files.readString(names, StandardCharsets.UTF_8);
                    if (!shared.contains("ShareA") || !shared.contains("namesStored")) {
                        return "the shared names file does not hold the names: " + shared;
                    }
                    if (Files.exists(profileDir(made[0]).resolve(names.getFileName()))) {
                        return "a profile folder carries the shared names file";
                    }
                    String inProfile = Files.readString(profileDir(made[0]).resolve(main.getFileName()), StandardCharsets.UTF_8);
                    if (inProfile.contains("ShareA")) {
                        return "the profile's Name Changer file carries the shared name: " + inProfile;
                    }
                    cfg = Mod.cfg(NC);
                    Mod.call(cfg, "setOwnDisplayName", "ShareB");
                    Mod.call(cfg, "save");
                    Mod.staticCall(PM, "saveCurrentAsProfile", made[1]);
                    Object result = Mod.staticCall(PM, "applyProfile", made[0]);
                    if (!(Boolean) Mod.call(result, "success")) {
                        return "applying " + made[0] + " failed: " + Mod.call(result, "message");
                    }
                    String after = name(Mod.cfg(NC));
                    if (!"ShareB".equals(after)) {
                        return "loading a profile changed the shared name to '" + after + "' (want ShareB)";
                    }
                    Mod.staticCall(PM, "applyProfile", made[1]);
                    if (!"ShareB".equals(name(Mod.cfg(NC)))) {
                        return "loading the second profile changed the shared name to '" + name(Mod.cfg(NC)) + "'";
                    }
                    c.note("Per-Profile Names off: two profile loads left the name on ShareB; the profile folders carry no names");

                    // Per-profile: each profile restores its own, and the switch is the same in every profile.
                    cfg = Mod.cfg(NC);
                    Mod.call(cfg, "setPerProfileNames", true);
                    Mod.call(cfg, "setOwnDisplayName", "PerA");
                    Mod.call(cfg, "save");
                    Mod.staticCall(PM, "saveCurrentAsProfile", made[2]);
                    cfg = Mod.cfg(NC);
                    Mod.call(cfg, "setOwnDisplayName", "PerB");
                    Mod.call(cfg, "save");
                    Mod.staticCall(PM, "saveCurrentAsProfile", made[3]);
                    if (!Files.readString(profileDir(made[2]).resolve(main.getFileName()), StandardCharsets.UTF_8).contains("PerA")) {
                        return "a per-profile profile does not carry its name";
                    }
                    Mod.staticCall(PM, "applyProfile", made[2]);
                    if (!"PerA".equals(name(Mod.cfg(NC))) || !(Boolean) Mod.call(Mod.cfg(NC), "isPerProfileNames")) {
                        return "profile 3 gave '" + name(Mod.cfg(NC)) + "', per-profile "
                                + Mod.call(Mod.cfg(NC), "isPerProfileNames");
                    }
                    Mod.staticCall(PM, "applyProfile", made[3]);
                    if (!"PerB".equals(name(Mod.cfg(NC)))) {
                        return "profile 4 gave '" + name(Mod.cfg(NC)) + "' (want PerB)";
                    }
                    // Back to shared: the names on screen move to the shared file, and no profile changes them.
                    Mod.call(Mod.cfg(NC), "setPerProfileNames", false);
                    Mod.call(Mod.cfg(NC), "save");
                    Mod.staticCall(PM, "applyProfile", made[2]);
                    if (!"PerB".equals(name(Mod.cfg(NC)))) {
                        return "after switching back to shared, a profile load changed the name to '" + name(Mod.cfg(NC)) + "'";
                    }
                    c.note("Per-Profile Names on: PerA and PerB restored per profile, the switch stayed on; off again: shared");

                    // A config from before this change moves into the shared file, once.
                    Files.deleteIfExists(names);
                    Files.writeString(main, "{\"enabled\":true,\"ownDisplayName\":\"OldNick\",\"ownColor\":-43691,"
                            + "\"mappings\":[{\"real\":\"Someone\",\"display\":\"Pal\",\"color\":0}]}", StandardCharsets.UTF_8);
                    Mod.staticCall(NC, "load");
                    Object old = Mod.cfg(NC);
                    if (!"OldNick".equals(name(old)) || ((java.util.List<?>) Mod.call(old, "mappings")).size() != 1) {
                        return "an old-format config did not load its names: '" + name(old) + "'";
                    }
                    if (!Files.exists(names) || !Files.readString(names, StandardCharsets.UTF_8).contains("OldNick")) {
                        return "the old names were not moved into the shared file";
                    }
                    return null;
                } catch (Exception e) {
                    throw new AssertionError(UiCase.describe(e), e);
                }
            });
            c.check(why == null, why);
        } finally {
            restore(main, mainBefore);
            restore(names, namesBefore);
            c.onClient(mc -> {
                for (String p : made) {
                    Mod.staticCall(PM, "deleteProfile", p);
                }
                try {
                    if (activeBefore == null) {
                        Files.deleteIfExists(markerPath);
                    } else {
                        Files.writeString(markerPath, activeBefore);
                    }
                } catch (Exception ignored) {
                    // nothing later reads it
                }
                Mod.staticCall(NC, "load");
                if (Quiet.enabled()) {
                    Quiet.apply();
                }
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
        }
    }
}
