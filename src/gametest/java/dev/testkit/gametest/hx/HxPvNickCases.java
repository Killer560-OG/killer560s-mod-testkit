package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Profile Viewer and Nickhider changes (killer560's BUGS AND CHANGES, 2026-10-08; mod branch pv-nick), 691-698:
 * <ul>
 *   <li>691: the bracketed skill level is the level with NO cap - past the table's 60, at the table's last step each.</li>
 *   <li>692: Nickhider "Randomized Name": a typed base name plus a number, unique per player and stable for the session
 *       (and kept when the base changes); painted through the real NameReplacer for a real tab-list player. The tab
 *       shows no explanatory text.</li>
 *   <li>693: /pv layout - every page tab on two rows inside the panel, and a name box a 16-character name fits in.</li>
 *   <li>694: the name box tab-completes from recent views: Tab fills the first match, Tab again the next, cycling.</li>
 *   <li>695: Backpacks drawn as clipped panels: nothing below the content box, though a panel row reaches its edge.</li>
 *   <li>696: Item Rarity's background is drawn behind PV items when that feature is on (pixel diff, on vs off).</li>
 *   <li>697: Basic Info's Networth line is the shared estimate - purse + bank + 64 x an injected Bazaar price.</li>
 *   <li>698: the Bestiary category list stays inside the box with 20 categories (the old one needed 380 px).</li>
 * </ul>
 * The profile is built here (Hypixel's v2 shape with real gzipped NBT inventory blobs) and put in the PV's cache, so
 * nothing goes to the network.
 */
final class HxPvNickCases {

    private static final String SCREEN = "profileviewer.screen.ProfileViewerScreen";
    private static final String API = "profileviewer.api.ProfileViewerApi";
    private static final String NC = "namechanger.NameChangerConfig";
    private static final String NF = "namechanger.NameChangerFeature";
    private static final UUID PV_UUID = UUID.fromString("6b1f3c2e-8d4a-4f1b-9c3e-2a7d5e9f0b11");
    private static final String PV_NAME = "PvTestkit";
    private static final long PURSE = 1_234_567L;
    private static final long BANK = 10_000_000L;
    private static final long EDIAMOND_PRICE = 1_000L;

    private HxPvNickCases() {
    }

    static void register(Session s) {
        s.test("691-hx-pv-uncapped-level", HxPvNickCases::uncapped);
        s.test("692-hx-nickhider-randomized-name", HxPvNickCases::randomizedName);
        s.test("693-hx-pv-layout", HxPvNickCases::layout);
        s.test("694-hx-pv-tab-complete", HxPvNickCases::tabComplete);
        s.test("695-hx-pv-backpacks-clipped", HxPvNickCases::backpacksClipped);
        s.test("696-hx-pv-item-rarity", HxPvNickCases::rarity);
        s.test("697-hx-pv-networth-line", HxPvNickCases::networth);
        s.test("698-hx-pv-bestiary-fits", HxPvNickCases::bestiary);
    }

    // ---- 691 ------------------------------------------------------------------------------------------------------

    private static void uncapped(Session c) {
        long[] t = c.onClient(mc -> {
            Mod.staticCall("profileviewer.data.LevelTables", "ensureLoaded");
            Object skill = Mod.staticCall("profileviewer.data.LevelTables", "skill", "FARMING");
            return skill == null ? null : (long[]) Mod.call(skill, "totalXp");
        });
        c.check(t != null && t.length >= 50, "Farming's XP table did not load (" + (t == null ? "null" : t.length) + ")");
        long last = t[t.length - 1];
        long step = last - t[t.length - 2];
        int at30 = c.onClient(mc -> (Integer) Mod.staticCall("profileviewer.data.LevelTables", "uncappedSkillLevel", "FARMING", t[29]));
        int past3 = c.onClient(mc -> (Integer) Mod.staticCall("profileviewer.data.LevelTables", "uncappedSkillLevel", "FARMING", last + 3 * step + 5));
        int justUnder = c.onClient(mc -> (Integer) Mod.staticCall("profileviewer.data.LevelTables", "uncappedSkillLevel", "FARMING", last + step - 1));
        c.note("table " + t.length + " levels, last step " + step + "; level-30 XP -> " + at30 + ", +3 steps -> " + past3
                + ", one step minus 1 -> " + justUnder);
        c.check(at30 == 30, "XP for exactly level 30 read as " + at30);
        c.check(past3 == t.length + 3, "three steps past the table read as " + past3 + ", expected " + (t.length + 3));
        c.check(justUnder == t.length, "one coin short of a step past the table read as " + justUnder);
        // The capped level stays the cap: the old bracket was the table's max, which is what this replaces.
        int capped = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("profileviewer.data.LevelTables", "skillLevel",
                "FARMING", last + 3 * step + 5, 52), "level"));
        c.check(capped == 52, "capped Farming level read as " + capped);
    }

    // ---- 692 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void randomizedName(Session c) {
        dev.testkit.gametest.TestEnemy bot = null;
        try (Settings set = new Settings(c).with(NC, "RandomizeOthers", true)
                .with(NC, "RandomizedName", true)
                .with(NC, "RandomBaseName", "Hxbase")) {
            dungeon(c);
            double[] at = c.scenario().playerPosition();
            bot = dev.testkit.gametest.TestEnemy.named("Rn", "B").at(at[0] - 2, at[1], at[2]).frozen(true)
                    .spawn(c.ctx(), c.server());
            c.waitUntil("randomize-others collected a player (premise)", mc -> {
                Object seen = Mod.field(NF, "SEEN_PLAYERS");
                synchronized (seen) {
                    return !((Set<String>) seen).isEmpty();
                }
            }, 80);
            String real = c.onClient(mc -> {
                Object seen = Mod.field(NF, "SEEN_PLAYERS");
                synchronized (seen) {
                    return ((Set<String>) seen).iterator().next();
                }
            });
            int n = c.onClient(mc -> (Integer) Mod.staticCall(NF, "numberFor", real));
            c.waitUntil("NameReplacer paints " + real + " as Hxbase" + n, mc -> {
                String out = (String) Mod.staticCall("namechanger.NameReplacer", "replace", "hi " + real + "!");
                return out != null && out.contains("Hxbase" + n) && !out.contains(real);
            }, 60);
            // Unique and stable over many names.
            List<Integer> first = c.onClient(mc -> {
                List<Integer> out = new ArrayList<>();
                for (int i = 0; i < 1500; i++) {
                    out.add((Integer) Mod.staticCall(NF, "numberFor", "Uniq" + i));
                }
                return out;
            });
            List<Integer> again = c.onClient(mc -> {
                List<Integer> out = new ArrayList<>();
                for (int i = 0; i < 1500; i++) {
                    out.add((Integer) Mod.staticCall(NF, "numberFor", "Uniq" + i));
                }
                return out;
            });
            Set<Integer> distinct = new HashSet<>(first);
            distinct.add(n);
            c.note(real + " -> Hxbase" + n + "; 1500 more names, " + distinct.size() + " distinct numbers (incl. " + real + ")");
            c.check(distinct.size() == 1501, "two players share a number: " + distinct.size() + " distinct of 1501");
            c.check(first.equals(again), "numbers changed between two lookups in one session");
            // A new base keeps the number.
            c.onClient(mc -> {
                Mod.set(NC, "setRandomBaseName", "Zqname");
                return null;
            });
            c.waitUntil("a new base keeps the same number", mc -> {
                String out = (String) Mod.staticCall("namechanger.NameReplacer", "replace", "hi " + real + "!");
                return out != null && out.contains("Zqname" + n);
            }, 40);
            // The tab: no explanatory text, a Randomized Name toggle and the base name box.
            List<String> kinds = c.onClient(mc -> {
                Object tab = newInstance("gui.tab.NickhiderTab");
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 10, 10, 200, (Runnable) () -> {
                });
                List<String> out = new ArrayList<>();
                for (AbstractWidget w : ws) {
                    out.add(w.getClass().getSimpleName() + ":" + w.getMessage().getString());
                }
                return out;
            });
            c.note("Nickhider tab widgets: " + kinds);
            c.check(kinds.stream().noneMatch(k -> k.startsWith(StringWidget.class.getSimpleName() + ":")),
                    "the Nickhider tab still shows a text line: " + kinds);
            c.check(kinds.stream().anyMatch(k -> k.contains("Randomized Name")), "no Randomized Name toggle: " + kinds);
            c.check(kinds.stream().anyMatch(k -> k.startsWith("EditBox:")), "no base name box with Randomized Name on: " + kinds);
        } finally {
            if (bot != null) {
                bot.remove();
            }
        }
    }

    // ---- the profile ----------------------------------------------------------------------------------------------

    private static CompoundTag item(String id, int count, String name, String rarityLine, String sbId) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putByte("Count", (byte) count);
        CompoundTag tag = new CompoundTag();
        CompoundTag display = new CompoundTag();
        display.putString("Name", name);
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("§7A testkit item."));
        lore.add(StringTag.valueOf(""));
        lore.add(StringTag.valueOf(rarityLine));
        display.put("Lore", lore);
        tag.put("display", display);
        CompoundTag extra = new CompoundTag();
        extra.putString("id", sbId);
        tag.put("ExtraAttributes", extra);
        t.put("tag", tag);
        return t;
    }

    private static JsonObject blob(List<CompoundTag> slots) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag s : slots) {
            list.add(s == null ? new CompoundTag() : s);
        }
        root.put("i", list);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, bytes);
        JsonObject o = new JsonObject();
        o.addProperty("type", 0);
        o.addProperty("data", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        return o;
    }

    /** Slot 0 of every backpack: a LEGENDARY sword; slot 1: 64 Enchanted Diamonds (priced in 697). 36 slots each. */
    private static JsonObject profileJson() throws Exception {
        JsonObject member = new JsonObject();
        member.addProperty("player_id", PV_UUID.toString().replace("-", ""));
        JsonObject currencies = new JsonObject();
        currencies.addProperty("coin_purse", PURSE);
        member.add("currencies", currencies);
        JsonObject exp = new JsonObject();
        exp.addProperty("SKILL_FARMING", 200_000_000L);
        exp.addProperty("SKILL_COMBAT", 1_000_000L);
        JsonObject pd = new JsonObject();
        pd.add("experience", exp);
        member.add("player_data", pd);

        JsonObject inv = new JsonObject();
        List<CompoundTag> main = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            main.add(null);
        }
        inv.add("inv_contents", blob(main));
        JsonObject backpacks = new JsonObject();
        for (int b = 0; b < 6; b++) {
            List<CompoundTag> slots = new ArrayList<>();
            slots.add(item("minecraft:diamond_sword", 1, "§6Testkit Blade", "§6§lLEGENDARY SWORD", "TESTKIT_BLADE"));
            slots.add(item("minecraft:diamond", 64, "§aEnchanted Diamond", "§a§lUNCOMMON", "ENCHANTED_DIAMOND"));
            for (int i = 2; i < 36; i++) {
                slots.add(null);
            }
            backpacks.add(String.valueOf(b), blob(slots));
        }
        inv.add("backpack_contents", backpacks);
        member.add("inventory", inv);

        JsonObject kills = new JsonObject();
        for (int i = 0; i < 20; i++) {
            kills.addProperty("tkmob" + i + "_1", 100 + i);
        }
        JsonObject bestiary = new JsonObject();
        bestiary.add("kills", kills);
        member.add("bestiary", bestiary);

        JsonObject profile = new JsonObject();
        profile.addProperty("profile_id", "6b1f3c2e-0000-4000-8000-000000000001");
        profile.addProperty("cute_name", "Testkit");
        profile.addProperty("selected", true);
        JsonObject banking = new JsonObject();
        banking.addProperty("balance", BANK);
        profile.add("banking", banking);
        JsonObject members = new JsonObject();
        members.add(PV_UUID.toString().replace("-", ""), member);
        profile.add("members", members);
        JsonArray profiles = new JsonArray();
        profiles.add(profile);
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.add("profiles", profiles);
        return root;
    }

    /** Parses the built profile with the mod's own parser and puts it in the PV's cache under {@link #PV_UUID}. */
    @SuppressWarnings("unchecked")
    private static void seedCache(Session c) throws Exception {
        JsonObject json = profileJson();
        c.onClient(mc -> {
            try {
                List<Object> parsed = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll", json, PV_UUID);
                Class<?> resCls = Mod.cls(API + "$ProfilesResult");
                Object result = resCls.getDeclaredConstructor(UUID.class, List.class, String.class, long.class)
                        .newInstance(PV_UUID, parsed, "Testkit", System.currentTimeMillis());
                Constructor<?> cachedCtor = Mod.cls(API + "$Cached").getDeclaredConstructor(resCls, long.class);
                cachedCtor.setAccessible(true);
                ((Map<UUID, Object>) Mod.field(API, "CACHE")).put(PV_UUID, cachedCtor.newInstance(result, System.currentTimeMillis()));
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not seed the PV cache: " + e, e);
            }
        });
    }

    private static Object newInstance(String cls) {
        try {
            Constructor<?> k = Mod.cls(cls).getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + cls + ": " + e, e);
        }
    }

    /** Opens /pv on the seeded profile at 1600x900, GUI 2 (800x450: the panel at full size and the sidebar shown),
     *  waits for READY, and returns the screen. */
    private static Screen openPv(Session c, int page) throws Exception {
        seedCache(c);
        Screen screen = c.onClient(mc -> {
            try {
                Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target").getDeclaredConstructor(String.class, UUID.class)
                        .newInstance(PV_NAME, PV_UUID);
                Screen s = (Screen) Mod.cls(SCREEN).getDeclaredConstructor(Screen.class, target.getClass()).newInstance(null, target);
                McCompat.setScreen(mc, s);
                return s;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open the PV: " + e, e);
            }
        });
        c.waitUntil("the PV shows the seeded profile", mc -> "READY".equals(String.valueOf(Mod.field(screen, "state"))), 100);
        c.onClient(mc -> {
            Mod.call(screen, "setPage", page);
            return null;
        });
        c.ctx().waitTicks(3);
        return screen;
    }

    private static int[] window;

    private static void bigWindow(Session c) {
        window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        setWindow(c, 1600, 900, 2);
    }

    private static void restore(Session c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        if (window != null) {
            setWindow(c, window[0], window[1], window[2]);
        }
    }

    private static void setWindow(Session c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        c.ctx().waitTicks(4);
    }

    private static int i(Object o, String f) {
        return (Integer) Mod.field(o, f);
    }

    // ---- 693 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void layout(Session c) throws Exception {
        bigWindow(c);
        try {
            Screen s = openPv(c, 0);
            String info = c.onClient(mc -> {
                List<int[]> tabs = (List<int[]>) Mod.field(s, "tabRects");
                int panelW = i(s, "panelW");
                StringBuilder sb = new StringBuilder("panel " + panelW + "x" + i(s, "panelH") + ", tabs");
                for (int[] t : tabs) {
                    sb.append(" [x").append(t[0]).append(" r").append(t[1]).append(" w").append(t[2]).append("]");
                }
                EditBox box = (EditBox) Mod.field(s, "switchBox");
                sb.append("; sidebar ").append(Mod.field(s, "sidebarVisible")).append(" w").append(i(s, "sidebarW"))
                        .append(", name box ").append(box == null ? "none" : box.getWidth() + " px");
                return sb.toString();
            });
            c.note(info);
            List<int[]> tabs = c.onClient(mc -> new ArrayList<>((List<int[]>) Mod.field(s, "tabRects")));
            int panelW = c.onClient(mc -> i(s, "panelW"));
            int pages = c.onClient(mc -> ((String[]) Mod.field(SCREEN, "PAGES")).length);
            c.check(tabs.size() == pages, tabs.size() + " tab rects for " + pages + " pages");
            for (int[] t : tabs) {
                c.check(t[1] >= 0 && t[1] <= 1, "a tab is on row " + t[1] + " (only rows 0 and 1 allowed)");
                c.check(t[0] >= 6 && t[0] + t[2] <= panelW - 5, "a tab runs outside the panel: x " + t[0] + " w " + t[2] + " panel " + panelW);
            }
            int contentY = c.onClient(mc -> i(s, "contentY"));
            int panelY = c.onClient(mc -> i(s, "panelY"));
            c.check(contentY == panelY + 29 + 2 * 21, "content starts at " + (contentY - panelY) + " below the panel top, not under two tab rows");
            int[] box = c.onClient(mc -> {
                EditBox b = (EditBox) Mod.field(s, "switchBox");
                return b == null ? null : new int[]{b.getWidth(), mc.font.width("WWWWWWWWWWWWWWWW")};
            });
            c.check(box != null, "the sidebar's name box is not shown at 800x450");
            c.check(box[0] - 8 >= box[1], "a 16-character name (" + box[1] + " px) does not fit the " + box[0] + " px box");
            Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name()));
            c.note("screenshot " + dev.testkit.harness.Report.screenshot(c.name(), shot));
        } finally {
            restore(c);
        }
    }

    // ---- 694 ------------------------------------------------------------------------------------------------------

    private static void tabComplete(Session c) throws Exception {
        UUID a = UUID.fromString("6b1f3c2e-8d4a-4f1b-9c3e-2a7d5e9f0b21");
        UUID b = UUID.fromString("6b1f3c2e-8d4a-4f1b-9c3e-2a7d5e9f0b22");
        c.onClient(mc -> {
            Object cfg = Mod.cfg("profileviewer.ProfileViewerConfig");
            Mod.call(cfg, "recordRecentView", a, "Elyfirsttestkit");
            Mod.call(cfg, "recordRecentView", b, "Elysecondtestkt");
            return null;
        });
        bigWindow(c);
        try {
            Screen s = openPv(c, 0);
            List<String> values = c.onClient(mc -> {
                EditBox box = (EditBox) Mod.field(s, "switchBox");
                s.setFocused(box);
                box.setFocused(true);
                box.setValue("ely");
                List<String> out = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    s.keyPressed(new KeyEvent(258, 0, 0));
                    out.add(box.getValue());
                }
                return out;
            });
            @SuppressWarnings("unchecked")
            List<String> sugg = c.onClient(mc -> new ArrayList<>((List<String>) Mod.field(s, "suggestions")));
            c.note("typed 'ely': suggestions " + sugg + "; Tab x3 -> " + values);
            c.check(sugg.contains("Elyfirsttestkit") && sugg.contains("Elysecondtestkt"), "the recent views were not suggested: " + sugg);
            c.check(sugg.contains(values.get(0)) && sugg.contains(values.get(1)) && !values.get(0).equals(values.get(1)),
                    "Tab did not fill two different matches: " + values);
            c.check(sugg.size() != 2 || values.get(2).equals(values.get(0)), "a third Tab did not cycle back: " + values);
            List<String> none = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<String> r = (List<String>) Mod.call(s, "completions", "zzqqxx");
                return r;
            });
            c.check(none.isEmpty(), "a prefix nobody has still suggested " + none);
        } finally {
            restore(c);
        }
    }

    // ---- pixels ---------------------------------------------------------------------------------------------------

    private static BufferedImage shot(Session c, String label) throws Exception {
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ctx().waitTicks(3);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        return javax.imageio.ImageIO.read(taken.toFile());
    }

    /** Pixels whose summed RGB moved by more than 30 between the two frames, in GUI rect x0..x1, y0..y1. */
    private static int changed(BufferedImage a, BufferedImage b, double scale, int x0, int y0, int x1, int y1) {
        int n = 0;
        int px0 = (int) Math.floor(x0 * scale), py0 = (int) Math.floor(y0 * scale);
        int px1 = Math.min(a.getWidth(), (int) Math.ceil(x1 * scale)), py1 = Math.min(a.getHeight(), (int) Math.ceil(y1 * scale));
        for (int y = Math.max(0, py0); y < py1; y++) {
            for (int x = Math.max(0, px0); x < px1; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Image pixels per GUI unit, from the screen's own laid-out width: the window can report a GUI size the frame was
     *  not drawn at (695's first run read 800 wide for a frame laid out 854 wide and measured below the box). */
    private static double scale(Session c, Screen s, BufferedImage img) {
        int guiW = c.onClient(mc -> s.width);
        return (double) img.getWidth() / guiW;
    }

    private static void showBackpacks(Session c, Screen s) {
        c.onClient(mc -> {
            setInstanceField(s, "invView", 2);
            setInstanceField(s, "invScroll", 0);
            return null;
        });
        c.waitUntil("the profile's items decoded", mc -> {
            Object p = Mod.call(s, "profile");
            CompletableFuture<?> f = (CompletableFuture<?>) Mod.call(p, "inventories");
            return f.isDone();
        }, 100);
        c.ctx().waitTicks(3);
    }

    // ---- 695 ------------------------------------------------------------------------------------------------------

    private static void backpacksClipped(Session c) throws Exception {
        bigWindow(c);
        try {
            Screen s = openPv(c, 0);
            BufferedImage basic = shot(c, "basic");
            c.onClient(mc -> {
                Mod.call(s, "setPage", 2);
                return null;
            });
            showBackpacks(c, s);
            BufferedImage packs = shot(c, "backpacks");
            double sc = scale(c, s, packs);
            int[] r = c.onClient(mc -> new int[]{i(s, "contentX"), i(s, "contentY"), i(s, "contentW"), i(s, "contentH"),
                    i(s, "panelY"), i(s, "panelH")});
            int rx = r[0] + 90;
            int rw = r[2] - 90;
            int boxBottom = r[1] + r[3];
            int insideBottom = changed(basic, packs, sc, rx + 2, boxBottom - 20, rx + rw - 6, boxBottom - 2);
            int below = changed(basic, packs, sc, rx, boxBottom + 1, rx + rw, boxBottom + 40);
            c.note("content box bottom y " + boxBottom + "; changed px in its last 18 rows " + insideBottom + ", below it " + below);
            c.check(insideBottom > 200, "premise: no backpack panel reached the bottom of the content box (" + insideBottom + " px)");
            c.check(below < 20, "backpack panels drew below the content box: " + below + " px changed");
        } finally {
            restore(c);
        }
    }

    // ---- 696 ------------------------------------------------------------------------------------------------------

    private static void rarity(Session c) throws Exception {
        String rc = "itemrarity.ItemRarityConfig";
        bigWindow(c);
        try (AutoCloseable sbo = c.onClient(mc -> Mod.with(rc, "SkyblockOnly", false));
             AutoCloseable on = c.onClient(mc -> Mod.with(rc, "Enabled", false))) {
            Screen s = openPv(c, 2);
            showBackpacks(c, s);
            Object rar = c.onClient(mc -> {
                Object p = Mod.call(s, "profile");
                Object inv = ((CompletableFuture<?>) Mod.call(p, "inventories")).getNow(null);
                Object bp = ((List<?>) Mod.call(inv, "backpacks")).get(0);
                Object stack = ((List<?>) Mod.call(bp, "items")).get(0);
                return Mod.staticCall("itemrarity.ItemRarityFeature", "getRarity", stack);
            });
            c.check(rar != null, "premise: the mod reads no rarity from the LEGENDARY test item");
            BufferedImage off = shot(c, "rarity-off");
            c.onClient(mc -> {
                Mod.set(rc, "setEnabled", true);
                return null;
            });
            BufferedImage onImg = shot(c, "rarity-on");
            double sc = scale(c, s, onImg);
            int[] r = c.onClient(mc -> new int[]{i(s, "contentX"), i(s, "contentY"), i(s, "contentW"), mc.font.lineHeight});
            int rx = r[0] + 90;
            int rw = r[2] - 90;
            int cols = Math.max(1, (rw - 8 + 6) / (166 + 6));
            int startX = rx + Math.max(0, (rw - (cols * 172 - 6)) / 2);
            int sx = startX + 3;
            int sy = r[1] + 22 + r[3] + 4 + 1;
            int slot = changed(off, onImg, sc, sx, sy, sx + 16, sy + 16);
            int empty = changed(off, onImg, sc, sx + 2 * 18, sy, sx + 2 * 18 + 16, sy + 16);
            c.note("rarity " + rar + "; slot 0 at " + sx + "," + sy + ": " + slot + " px changed with Item Rarity on; empty slot 2: " + empty);
            c.check(slot > 20, "no rarity background behind the LEGENDARY item in the PV (" + slot + " px)");
            c.check(empty < 5, "an empty slot changed with Item Rarity on (" + empty + " px) - the diff is not the rarity");
        } finally {
            restore(c);
        }
    }

    // ---- 697 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void networth(Session c) throws Exception {
        Map<String, Long> bazaar = c.onClient(mc -> (Map<String, Long>) Mod.field(
                Mod.field("rngmeter.RngMeterEngine", "PRICES"), "bazaarSellPrice"));
        Long before = bazaar.put("ENCHANTED_DIAMOND", EDIAMOND_PRICE);
        bigWindow(c);
        try {
            Screen s = openPv(c, 0);
            c.waitUntil("the Basic Info networth line has a value", mc -> Mod.field(s, "lastBasicNetworth") != null, 200);
            long shown = c.onClient(mc -> (Long) Mod.field(s, "lastBasicNetworth"));
            long items = 6 * 64 * EDIAMOND_PRICE;
            long expected = PURSE + BANK + items;
            c.note("networth line " + shown + "; purse " + PURSE + " + bank " + BANK + " + 6 x 64 Enchanted Diamond at " + EDIAMOND_PRICE
                    + " = " + expected);
            c.check(shown == expected, "the networth line shows " + shown + ", expected " + expected);
            // The same number on the Networth page (shared calculation).
            Object state = c.onClient(mc -> Mod.call(Mod.field(s, "extraPages"), "networthState", Mod.call(s, "profile")));
            Object res = Mod.call(state, "result");
            long page = (Long) Mod.call(res, "total");
            c.check(page == shown, "the Networth page total " + page + " differs from the Basic Info line " + shown);
            c.note("networth page total " + page + " (same estimate)");
        } finally {
            if (before == null) {
                bazaar.remove("ENCHANTED_DIAMOND");
            } else {
                bazaar.put("ENCHANTED_DIAMOND", before);
            }
            restore(c);
        }
    }

    // ---- 698 ------------------------------------------------------------------------------------------------------

    private static void bestiary(Session c) throws Exception {
        Object remote = c.onClient(mc -> Mod.field("profileviewer.data.ExtraTables", "BESTIARY"));
        Field future = remote.getClass().getDeclaredField("future");
        Field started = remote.getClass().getDeclaredField("startedAt");
        future.setAccessible(true);
        started.setAccessible(true);
        Object oldFuture = future.get(remote);
        long oldStarted = started.getLong(remote);
        bigWindow(c);
        try {
            List<Object> cats = c.onClient(mc -> {
                try {
                    Class<?> famCls = Mod.cls("profileviewer.data.ExtraTables$BestiaryFamily");
                    Class<?> catCls = Mod.cls("profileviewer.data.ExtraTables$BestiaryCategory");
                    Constructor<?> fam = famCls.getDeclaredConstructor(String.class, String.class, String.class, int.class, List.class, int[].class);
                    Constructor<?> cat = catCls.getDeclaredConstructor(String.class, String.class, String.class, String.class, List.class);
                    List<Object> out = new ArrayList<>();
                    for (int k = 0; k < 20; k++) {
                        Object f = fam.newInstance("Testkit Mob " + k, "ROTTEN_FLESH", null, 10, List.of("tkmob" + k + "_1"), new int[]{10, 100, 1000});
                        out.add(cat.newInstance("tk" + k, "Testkit Area " + k, "ROTTEN_FLESH", null, List.of(f)));
                    }
                    return out;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot build bestiary categories: " + e, e);
                }
            });
            synchronized (remote) {
                future.set(remote, CompletableFuture.completedFuture(cats));
                started.setLong(remote, System.currentTimeMillis());
            }
            Screen s = openPv(c, 6);
            c.waitUntil("the bestiary list was drawn", mc -> (Integer) Mod.field(Mod.field(s, "extraPages"), "lastBestiaryNavBottom") > 0, 100);
            int bottom = c.onClient(mc -> (Integer) Mod.field(Mod.field(s, "extraPages"), "lastBestiaryNavBottom"));
            int boxBottom = c.onClient(mc -> i(s, "contentY") + i(s, "contentH"));
            int contentY = c.onClient(mc -> i(s, "contentY"));
            c.note("20 categories: list bottom " + bottom + ", content box bottom " + boxBottom + " (the old 19 px rows would end at "
                    + (contentY + 4 + 20 * 19 + 44) + ")");
            c.check(bottom <= boxBottom - 2, "the bestiary category list runs to " + bottom + ", past the box bottom " + boxBottom);
            shot(c, "bestiary");
        } finally {
            synchronized (remote) {
                future.set(remote, oldFuture);
                started.setLong(remote, oldStarted);
            }
            restore(c);
        }
    }
}
