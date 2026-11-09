package dev.testkit.gametest.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * untestbugs-1109, the second half: Shortbow ids (Object Hider), Friends List scan marking, Auto Leap's per-leap Target
 * Mode, Item Quality on a container hover, the Etherwarp Overlay's shape boxes and Chat Tabs under suggestions. Each case
 * asserts the feature acted and checks the control next to it.
 */
final class UntestBugs1109MoreCases {

    private static final String FLC = "fastleap.FastLeapConfig";

    private UntestBugs1109MoreCases() {
    }

    private static ItemStack withTag(ItemStack stack, String key, Object value) {
        CompoundTag tag = new CompoundTag();
        if (value instanceof String s) {
            tag.putString(key, s);
        } else {
            tag.putInt(key, (Integer) value);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** No Shortbow Swing's id test: the Terminator (no SHORTBOW in its id) and every id ending in SHORTBOW; nothing else. */
    static void shortbows(UiCase c) throws Exception {
        String cls = "trajectories.Shortbows";
        boolean term = (Boolean) Mod.staticCall(cls, "isShortbowId", withTag(new ItemStack(Items.BOW), "id", "TERMINATOR"));
        boolean juju = (Boolean) Mod.staticCall(cls, "isShortbowId", withTag(new ItemStack(Items.BOW), "id", "JUJU_SHORTBOW"));
        boolean sword = (Boolean) Mod.staticCall(cls, "isShortbowId", withTag(new ItemStack(Items.IRON_SWORD), "id", "ASPECT_OF_THE_END"));
        boolean mid = (Boolean) Mod.staticCall(cls, "isShortbowId", withTag(new ItemStack(Items.BOW), "id", "SHORTBOW_FAKE"));
        boolean plain = (Boolean) Mod.staticCall(cls, "isShortbowId", new ItemStack(Items.BOW));
        c.check(term, "the Terminator is not matched as a shortbow");
        c.check(juju, "JUJU_SHORTBOW is not matched");
        c.check(!sword && !mid && !plain, "a non-shortbow matched (sword " + sword + ", SHORTBOW_FAKE " + mid + ", plain bow " + plain + ")");
    }

    /** The first offline friend of a scan marks every friend not yet read offline at once; the ones read stay as they were. */
    static void friends(UiCase c) throws Exception {
        String cfgName = "social.FriendsListConfig";
        Object cfg = Mod.cfg(cfgName);
        List<String> names = List.of("UbPalA", "UbPalB", "UbPalC", "UbPalD");
        List<?> made = (List<?>) Mod.call(cfg, "applyRealSync", names, false);
        for (Object f : made) {
            f.getClass().getField("onlineHint").set(f, Boolean.TRUE);
            f.getClass().getField("activity").set(f, "SkyBlock - Hub");
        }
        Mod.call(cfg, "markUnseenOffline", Set.of("ubpala", "ubpalb"));
        for (int i = 0; i < made.size(); i++) {
            Object f = made.get(i);
            boolean online = (Boolean) f.getClass().getField("onlineHint").get(f);
            String act = String.valueOf(f.getClass().getField("activity").get(f));
            if (i < 2) {
                c.check(online && !act.equals("Offline"), names.get(i) + " (already read) was changed: online " + online + " " + act);
            } else {
                c.check(!online && act.equals("Offline"), names.get(i) + " (not read yet) was not marked offline: online " + online + " " + act);
            }
        }
    }

    /** Auto Leap's per-leap Target Mode: an old file's one global mode becomes each leap's own, names turn into classes or
     *  nothing, one leap's mode changes alone and saves under its own entry, and the page has one Mode button per leap. */
    static void leapModes(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            return;
        }
        Path file = (Path) Mod.field(FLC, "CONFIG_PATH");
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        Object tmode = null;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"enabled\":true,\"targetMode\":\"POSMSG\",\"targets\":{"
                    + "\"S1\":{\"name\":\"SomeName\",\"byClass\":false},\"S2\":{\"class\":\"MAGE\",\"byClass\":true}}}", StandardCharsets.UTF_8);
            Mod.staticCall(FLC, "load");
            Object cfg = Mod.staticCall(FLC, "getInstance");
            Object posmsg = Mod.enumValue(FLC + "$TargetMode", "POSMSG");
            Object perLeap = Mod.enumValue(FLC + "$TargetMode", "PER_LEAP");
            Object s1 = Mod.enumValue(FLC + "$LeapTarget", "S1");
            Object s2 = Mod.enumValue(FLC + "$LeapTarget", "S2");
            Object s3 = Mod.enumValue(FLC + "$LeapTarget", "S3");
            c.check(posmsg.equals(Mod.call(cfg, "getTargetMode", s1)) && posmsg.equals(Mod.call(cfg, "getTargetMode", s2)),
                    "the old global POSMSG did not become each leap's mode: S1 " + Mod.call(cfg, "getTargetMode", s1)
                            + ", S2 " + Mod.call(cfg, "getTargetMode", s2));
            c.check(Mod.call(cfg, "getTargetClass", s2) != null && String.valueOf(Mod.call(cfg, "getTargetClass", s2)).equals("MAGE"),
                    "S2's class MAGE was lost: " + Mod.call(cfg, "getTargetClass", s2));
            c.check(!"SomeName".equals(Mod.call(cfg, "getTargetName", s1)), "S1 still goes by the name SomeName");
            Mod.call(cfg, "setTargetMode", s1, perLeap);
            c.check(perLeap.equals(Mod.call(cfg, "getTargetMode", s1)) && posmsg.equals(Mod.call(cfg, "getTargetMode", s2))
                    && posmsg.equals(Mod.call(cfg, "getTargetMode", s3)), "changing S1's mode changed another leap's");
            Mod.call(cfg, "save");
            JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            c.check(!saved.has("targetMode"), "the file still carries the global targetMode");
            JsonObject t = saved.getAsJsonObject("targets");
            c.check("PER_LEAP".equals(t.getAsJsonObject("S1").get("mode").getAsString())
                            && "POSMSG".equals(t.getAsJsonObject("S2").get("mode").getAsString()),
                    "the saved per-leap modes are wrong: S1 " + t.getAsJsonObject("S1") + " S2 " + t.getAsJsonObject("S2"));
            c.check(!t.getAsJsonObject("S1").has("name") || t.getAsJsonObject("S1").get("name").getAsString().isEmpty(),
                    "a player name is still saved: " + t.getAsJsonObject("S1"));

            // The Universal page: one Mode button per leap, and no card-wide "Target Mode" row.
            List<AbstractWidget> ws = new ArrayList<>();
            c.onClient(mc -> Mod.staticCall("fastleap.FastLeapSection", "buildUniversalTargets", ws, 0, 0, 400, false));
            int modes = 0;
            boolean globalRow = false;
            for (AbstractWidget w : ws) {
                String l = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                modes += l != null && l.startsWith("Mode: ") ? 1 : 0;
                globalRow |= l != null && l.startsWith("Target Mode");
            }
            int leaps = ((Object[]) Mod.cls(FLC + "$LeapTarget").getEnumConstants()).length;
            c.check(modes == leaps, "the Universal page has " + modes + " Mode buttons, one per leap (" + leaps + ") expected");
            c.check(!globalRow, "a card-wide Target Mode row is still on the page");
        } finally {
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            Mod.staticCall(FLC, "load");
        }
    }

    /** Item Quality's line reaches a container slot hover (the Auction House, the Museum): the tooltip a container screen
     *  builds carries it once, and an item with no quality gets none. */
    @SuppressWarnings("unchecked")
    static void itemQualityContainer(UiCase c) throws Exception {
        Object q = Mod.cfg("dungeonqol.DungeonQolConfig");
        boolean was = (Boolean) Mod.call(q, "isItemQuality");
        try {
            Mod.call(q, "setItemQuality", true);
            ItemStack good = withTag(new ItemStack(Items.DIAMOND_SWORD), "baseStatBoostPercentage", 45);
            ItemStack none = withTag(new ItemStack(Items.DIAMOND_SWORD), "id", "PLAIN");
            List<Component>[] out = new List[2];
            c.onClient(mc -> {
                Screen s = new InventoryScreen(mc.player);
                McCompat.setScreen(mc, s);
                Method m = null;
                for (Class<?> k = s.getClass(); k != null && m == null; k = k.getSuperclass()) {
                    for (Method d : k.getDeclaredMethods()) {
                        if (d.getName().equals("getTooltipFromContainerItem") && d.getParameterCount() == 1) {
                            m = d;
                        }
                    }
                }
                if (m == null) {
                    throw new AssertionError("no getTooltipFromContainerItem on " + s.getClass());
                }
                try {
                    m.setAccessible(true);
                    out[0] = (List<Component>) m.invoke(s, good);
                    out[1] = (List<Component>) m.invoke(s, none);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            long lines = out[0].stream().filter(l -> l.getString().startsWith("Item Quality: ")).count();
            c.check(lines == 1, "the container hover has " + lines + " Item Quality lines, want 1: " + out[0]);
            c.check(out[0].stream().anyMatch(l -> l.getString().contains("45/50")), "the line does not say 45/50: " + out[0]);
            c.check(out[1].stream().noneMatch(l -> l.getString().startsWith("Item Quality: ")), "an item with no quality got a line");
        } finally {
            Mod.call(q, "setItemQuality", was);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Etherwarp Overlay with Full Block Box OFF: a stair draws its real boxes (more than one), a full block one. */
    @SuppressWarnings("unchecked")
    static void etherShape(UiCase c) throws Exception {
        List<Integer> counts = c.onClient(mc -> {
            BlockPos p = mc.player.blockPosition().offset(3, 6, 3);
            var level = mc.level;
            var old = level.getBlockState(p);
            try {
                level.setBlock(p, Blocks.OAK_STAIRS.defaultBlockState(), 0);
                int stairs = ((List<?>) Mod.staticCall("etherwarpoverlay.EtherwarpOverlayFeature", "realBoxesFor", level, p)).size();
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 0);
                int stone = ((List<?>) Mod.staticCall("etherwarpoverlay.EtherwarpOverlayFeature", "realBoxesFor", level, p)).size();
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 0);
                int air = ((List<?>) Mod.staticCall("etherwarpoverlay.EtherwarpOverlayFeature", "realBoxesFor", level, p)).size();
                return List.of(stairs, stone, air);
            } finally {
                level.setBlock(p, old, 0);
            }
        });
        c.check(counts.get(0) >= 2, "a stair gave " + counts.get(0) + " boxes, want its real shape (2+)");
        c.check(counts.get(1) == 1, "a stone block gave " + counts.get(1) + " boxes, want 1");
        c.check(counts.get(2) == 1, "air gave " + counts.get(2) + " boxes, want the one full cube");
    }

    /** Chat Tabs under command suggestions: with suggestions open the feature sees their rectangle, a point in it counts as
     *  the suggestion list (so a click there picks the suggestion, not a tab) and a point outside it does not. */
    static void chatSuggestions(UiCase c) throws Exception {
        Object ct = Mod.cfg("chattabs.ChatTabsConfig");
        boolean on = (Boolean) Mod.call(ct, "isEnabledRaw");
        try {
            c.onClient(mc -> {
                Mod.call(ct, "setEnabled", true);
                McCompat.setScreen(mc, new ChatScreen("/p", false));
                return null;
            });
            int[] r = null;
            for (int i = 0; i < 40 && r == null; i++) {
                c.ticks(2);
                r = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    openSuggestions(s);
                    Frames.extract(mc, s, -1, -1);
                    Object rect = invokeRect(s);
                    return rect == null ? null : new int[]{(Integer) Mod.call(rect, "getX"), (Integer) Mod.call(rect, "getY"),
                            (Integer) Mod.call(rect, "getWidth"), (Integer) Mod.call(rect, "getHeight")};
                });
            }
            c.check(r != null, "no command suggestion list opened for \"/p\"");
            if (r == null) {
                return;
            }
            int[] box = r;
            boolean in = c.onClient(mc -> (Boolean) invokeAt(McCompat.screen(mc), box[0] + box[2] / 2.0, box[1] + box[3] / 2.0));
            boolean out = c.onClient(mc -> (Boolean) invokeAt(McCompat.screen(mc), box[0] - 10.0, box[1] - 10.0));
            c.check(in, "a point inside the suggestion list is not treated as the list");
            c.check(!out, "a point outside the suggestion list is treated as the list");
        } finally {
            Mod.call(ct, "setEnabled", on);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Auto-suggest may be off in a test client: ask the screen's CommandSuggestions to show its list, as the Tab key does. */
    private static void openSuggestions(Screen s) {
        try {
            java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("commandSuggestions");
            f.setAccessible(true);
            Object cs = f.get(s);
            Method m = cs.getClass().getDeclaredMethod("showSuggestions", boolean.class);
            m.setAccessible(true);
            m.invoke(cs, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the next pass tries again; the case fails on its own check when no list ever opens
        }
    }

    private static Object invokeRect(Screen s) {
        try {
            Method m = Mod.cls("chattabs.ChatTabsFeature").getDeclaredMethod("suggestionRect", Screen.class);
            m.setAccessible(true);
            return m.invoke(null, s);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeAt(Screen s, double x, double y) {
        try {
            Method m = Mod.cls("chattabs.ChatTabsFeature").getDeclaredMethod("suggestionsAt", Screen.class, double.class, double.class);
            m.setAccessible(true);
            return m.invoke(null, s, x, y);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
