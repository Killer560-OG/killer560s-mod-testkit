package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory Sorter layouts select a Hypixel loadout (killer560, 2026-11-04: "allow invsort to select a loadout"):
 * <ul>
 *   <li>1104-invsort-loadout-save: the layout's loadout is picked in the menu's Loadout picker (a click on slot 1-12 or None), saved in the layout file, survives a reload from disk and a rename.</li>
 *   <li>1104-invsort-loadout-apply: applying a layout with loadout 5 sends /loadouts, clicks the faked "(1/2) Loadout"
 *       menu's slot 24 (loadout 5), closes it, and only then sorts (control: the same layout with no loadout sends no
 *       /loadouts and no menu click).</li>
 * </ul>
 */
final class InvLoadout1104Cases {

    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String SCREEN = "invsort.InventorySorterScreen";
    private static final String CACHE = "invsort.InvSortLoadoutCache";
    private static final String ISC = "invsort.InventorySorterConfig";

    private InvLoadout1104Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1104-invsort-loadout-save", InvLoadout1104Cases::save);
        MenuSuite.test(s, "1104-invsort-loadout-apply", InvLoadout1104Cases::apply);
        MenuSuite.test(s, "1105-invsort-loadout-cache", InvLoadout1104Cases::cache);
        MenuSuite.test(s, "1105-invsort-loadout-equipped", InvLoadout1104Cases::equipped);
    }

    private static Object layout(Session c, String name, Map<Integer, String> slots, int loadout) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class, Map.class, Map.class,
                        Set.class, boolean.class, String.class, int.class)
                        .newInstance(name, slots, Map.of(), Map.of(), Set.of(), false, null, loadout);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object store() {
        return Mod.staticCall(STORE, "getInstance");
    }

    private static int loadoutOf(Session c, String name) {
        return c.onClient(mc -> {
            Object l = Mod.call(store(), "get", name);
            return l == null ? -1 : (Integer) Mod.call(l, "loadout");
        });
    }

    /** Opens the picker from the editor's Loadout button and really clicks choice n (1-12, 0 = None) in it. */
    private static void pick(Session c, int n) {
        c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "openLoadoutPicker"));
        c.waitUntil("the loadout picker", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InvSortLoadoutPick"), 40);
        c.ctx().runOnClient(mc -> {
            Object scr = McCompat.screen(mc);
            int[] at = (int[]) Mod.call(scr, "centerOf", n); // the middle of that loadout's cell (or None) as drawn now
            double x = at[0];
            double y = at[1];
            ((Screen) scr).mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
        });
        c.waitUntil("back in the layouts menu", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
    }

    private static boolean legit(Session c) {
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return true;
        }
        return false;
    }

    static void save(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1104-lo";
        String renamed = "hx-1104-lo2";
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                Mod.call(store(), "delete", renamed);
                Mod.call(store(), "save", layout(c, name, Map.of(0, "RUNEBOOK"), 0));
            });
            c.check(loadoutOf(c, name) == 0, "a new layout should have no loadout, got " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> {
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "select", name));
            c.ctx().waitTicks(3);
            pick(c, 5);
            c.check(loadoutOf(c, name) == 5, "a click on slot 5 should give loadout 5, got " + loadoutOf(c, name));
            String five = c.onClient(mc -> {
                try {
                    return Files.readString(((Path) Mod.staticCall(STORE, "directory")).resolve(name + ".json"));
                } catch (Exception e) {
                    return "ERR " + e;
                }
            });
            c.check(five.contains("\"loadout\": 5") || five.contains("\"loadout\":5"),
                    "the layout file does not hold loadout 5: " + five);
            pick(c, 12);
            c.check(loadoutOf(c, name) == 12, "a click on slot 12 should give loadout 12, got " + loadoutOf(c, name));
            pick(c, 0);
            c.check(loadoutOf(c, name) == 0, "a click on None should clear it, got " + loadoutOf(c, name));
            pick(c, 7);
            c.check(loadoutOf(c, name) == 7, "expected 7, got " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "onClose"));

            // The disk: the layout file says it, and a reload from disk brings it back.
            String json = c.onClient(mc -> {
                try {
                    return Files.readString(((Path) Mod.staticCall(STORE, "directory")).resolve(name + ".json"));
                } catch (Exception e) {
                    return "ERR " + e;
                }
            });
            c.check(json.contains("\"loadout\": 7") || json.contains("\"loadout\":7"),
                    "the layout file does not hold loadout 7: " + json);
            c.ctx().runOnClient(mc -> Mod.staticCall(STORE, "reload"));
            c.check(loadoutOf(c, name) == 7, "after a reload from disk the loadout is " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> Mod.call(store(), "rename", name, renamed));
            c.check(loadoutOf(c, renamed) == 7, "after a rename the loadout is " + loadoutOf(c, renamed));
            c.note("loadout picked 5,12,None,7 in the picker, written to the layout file, kept over reload and rename");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                Mod.call(store(), "delete", renamed);
            });
            MenuKit.reset(c);
        }
    }

    static void apply(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1104-apply";
        String plain = "hx-1104-plain";
        Path cfgPath = c.onClient(mc -> (Path) Mod.field(ISC, "CONFIG_PATH"));
        byte[] old = null;
        try {
            if (Files.exists(cfgPath)) {
                old = Files.readAllBytes(cfgPath);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        try {
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setTicksBetweenMoves", 2);
                Mod.call(cfg, "setRandomExtraTicks", 0);
                Mod.call(cfg, "setInstant", false);
            });
            c.hx().give(3, "minecraft:diamond");
            c.waitUntil("the diamond", mc -> mc.player.getInventory().getItem(3).is(Items.DIAMOND), 80);
            String diamond = c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of",
                    mc.player.getInventory().getItem(3)));
            Map<Integer, String> want = Map.of(0, diamond);

            // Control: no loadout, so no /loadouts and no menu click, and the diamond still moves.
            int before = c.events("container.click").size();
            int cmdsBefore = c.commands().size();
            Object plainLayout = layout(c, plain, want, 0);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", plainLayout)), "control start false");
            c.waitUntil("the control sort", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 300);
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)),
                    "control: the diamond did not reach slot 0");
            c.check(!c.commands().subList(cmdsBefore, c.commands().size()).contains("loadouts"),
                    "control: a layout without a loadout sent /loadouts");
            List<JsonObject> controlClicks = c.events("container.click").subList(before, c.events("container.click").size());
            c.check(!controlClicks.isEmpty(), "control: the sort sent no click, so it proves nothing");

            // Back to the diamond in slot 3, then the same with loadout 5.
            InventoryCases.clearInv(c);
            c.hx().give(3, "minecraft:diamond");
            c.waitUntil("the diamond again", mc -> mc.player.getInventory().getItem(3).is(Items.DIAMOND), 80);
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", MenuKit.menu("menus.loadout"));
            before = c.events("container.click").size();
            int closesBefore = c.events("container.close").size();
            cmdsBefore = c.commands().size();
            Object loLayout = layout(c, name, want, 5);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", loLayout)), "start returned false");
            c.waitUntil("the loadout then the sort", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 600);
            c.ctx().waitTicks(10);
            List<String> cmds = c.commands().subList(cmdsBefore, c.commands().size());
            c.check(cmds.stream().filter("loadouts"::equals).count() == 1, "want exactly one /loadouts, server saw " + cmds);
            List<JsonObject> clicks = c.events("container.click").subList(before, c.events("container.click").size());
            c.check(clicks.size() >= 2, "want the loadout click then sort clicks, got " + clicks);
            JsonObject first = clicks.get(0);
            c.check(first.get("slot").getAsInt() == 24 && first.get("input").getAsString().equals("PICKUP"),
                    "the first click should be PICKUP on menu slot 24 (loadout 5), got " + first);
            c.check(c.events("container.close").size() > closesBefore, "the loadout menu was not closed");
            JsonObject last = clicks.get(clicks.size() - 1);
            c.check(last.get("tick").getAsLong() > first.get("tick").getAsLong(),
                    "the sort did not come after the loadout click: " + clicks);
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)),
                    "the diamond did not reach slot 0 after the loadout");
            // The open menu was Hypixel's page 1 ("(1/2) Loadout"): the sorter's cache took a copy of it.
            c.waitUntil("the loadout cache", mc -> Mod.staticCall(CACHE, "get") != null, 60);
            c.note("loadout 5: /loadouts x1, first click PICKUP menu slot 24 at tick " + first.get("tick") + ", menu closed, "
                    + (clicks.size() - 1) + " sort click(s) after it, diamond in slot 0; control sent no /loadouts");
        } finally {
            if (c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"))) {
                c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
            }
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", null);
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
            try {
                if (old == null) {
                    Files.deleteIfExists(cfgPath);
                } else {
                    Files.write(cfgPath, old);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(ISC, "load"));
        }
    }

    /** A fake "(1/3) Loadouts" page: one distinct item per loadout in slots 14-16, 23-25, 32-34, 41-43. */
    private static List<ItemStack> fakePage() {
        Item[] kinds = {Items.DIAMOND_HELMET, Items.IRON_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.CHAINMAIL_BOOTS};
        ItemStack[] page = new ItemStack[54];
        java.util.Arrays.fill(page, ItemStack.EMPTY);
        int[] slots = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};
        for (int i = 0; i < slots.length; i++) {
            page[slots[i]] = new ItemStack(kinds[i / 3], 1);
        }
        return java.util.Arrays.asList(page);
    }

    /** loadoutpick-1105: the picker draws the cached /loadouts page, the cache survives a "restart", a click on a loadout's
     *  cell picks it, and with nothing cached the number buttons stay. */
    static void cache(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1105-lo";
        Path file = c.onClient(mc -> (Path) Mod.field(CACHE, "FILE"));
        byte[] old = null;
        try {
            if (Files.exists(file)) {
                old = Files.readAllBytes(file);
                Files.delete(file);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        try {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(CACHE, "forgetMemoryForTest");
                Mod.call(store(), "delete", name);
                Mod.call(store(), "save", layout(c, name, Map.of(0, "RUNEBOOK"), 0));
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "select", name));
            c.ctx().waitTicks(3);
            // Empty cache: the number buttons, and a click on 3 picks 3.
            c.check(c.onClient(mc -> Mod.staticCall(CACHE, "get")) == null, "an empty cache should answer null");
            pick(c, 3);
            c.check(loadoutOf(c, name) == 3, "fallback: a click on button 3 should give 3, got " + loadoutOf(c, name));
            int[] fallback5 = openPickerCenter(c, 5);
            c.ctx().runOnClient(mc -> McCompat.screen(mc).onClose());

            // Page seen: kept in memory and written to disk.
            List<ItemStack> page = fakePage();
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "capture", page));
            c.check(c.onClient(mc -> Mod.staticCall(CACHE, "get")) != null, "after capture the cache should answer");
            c.ctx().runOnClient(mc -> Mod.staticCall("shared.SharedWriter", "drain", 4000L));
            String json = Files.exists(file) ? Files.readString(file) : "";
            c.check(json.length() > 20, "the cache file was not written: " + file);
            // "Restart": forget memory, the next get reads the file.
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "forgetMemoryForTest"));
            c.waitUntil("the cache read back from disk", mc -> {
                Object got = Mod.staticCall(CACHE, "get");
                return got != null && ((List<?>) got).size() == 54;
            }, 100);
            List<?> back = c.onClient(mc -> (List<?>) Mod.staticCall(CACHE, "get"));
            c.check(((ItemStack) back.get(24)).is(Items.IRON_CHESTPLATE) && ((ItemStack) back.get(14)).is(Items.DIAMOND_HELMET)
                            && ((ItemStack) back.get(0)).isEmpty(),
                    "the reloaded page is not the saved one: slot 24 " + back.get(24) + ", slot 14 " + back.get(14));

            // The picker now shows the page: loadout 5's cell is elsewhere than its fallback button, and a click on it picks 5.
            int[] shown5 = openPickerCenter(c, 5);
            c.check(shown5[0] != fallback5[0] || shown5[1] != fallback5[1], "the picker still draws the number buttons");
            c.ctx().runOnClient(mc -> {
                Object scr = McCompat.screen(mc);
                ((Screen) scr).mouseClicked(new net.minecraft.client.input.MouseButtonEvent(shown5[0] + 5, shown5[1] - 6,
                        new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
            });
            c.waitUntil("back in the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.check(loadoutOf(c, name) == 5, "a click inside loadout 5's cell should pick 5, got " + loadoutOf(c, name));
            pick(c, 0);
            c.check(loadoutOf(c, name) == 0, "None should still clear it, got " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> McCompat.screen(mc).onClose());
            c.note("picker: number buttons with an empty cache; cache of a fake page saved to disk, read back after a restart, "
                    + "drawn as its cells (loadout 5 moved " + java.util.Arrays.toString(fallback5) + " -> "
                    + java.util.Arrays.toString(shown5) + "), a click in the cell picked 5, None cleared");
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(store(), "delete", name));
            try {
                if (old == null) {
                    Files.deleteIfExists(file);
                } else {
                    Files.write(file, old);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "forgetMemoryForTest"));
            MenuKit.reset(c);
        }
    }

    private static final String SWAP = "loadoutkeybinds.LoadoutSwapper";
    private static final int[] LOADOUT_SLOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};
    /** His loadout 1's head as his /loadouts cache held it on 2026-11-05 (killer560smod-loadout-cache.json, read from a copy). */
    private static final String HIS_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYwNTU0MzM0MTg4MSwKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdjZWI4ZjA3NThlMmQ4YWM0OWRlNmY5Nzc2MDNjN2JmYzIzZmQ4MmE4NTc0ODEwYTQ1ZjVlOTdjNjQzNmQ3OSIKICAgIH0KICB9Cn0=";
    private static final String HIS_SIGNATURE = "mqJHuSSijxc6VikSP8VL+tISFxCoCe6FA1s0ysbo7kp+eE8qYy7vRQTBqAI1rTSQmgQn1Oy0n+tFVFa7ToZSNVBM5VTllx6Nt1cipRTC4NkY4cilcqUeMddt1ao+jckKWsZ8nUD/8m9CCNtagldPKB5mhA458sN59j7iCTI1nR97cgDlIXZh9vDNG8/isTnDyzmpfyYUdSDj2+cSoILua+pgh7TVc/5Hg50kawkQ4dL1F4J05dHlA5eOnUgP352oWAYHmMMgblLY59n4/TkyyfSsz8YGd93enM+egfqQ2ipJU7Tgo0WhhbEdwE44BAi/mi1L2wW4YV18yJ7AxE4cvm9RxFM8Q1B9YBJ7MSUcwqvlf3kxoywaVbLBaOkeHrKIAKkqcyFljyDbLwTfXCAXhmBG0X0HMi+wPQCKki1oONMiZ0A1tJV5IzF/lOWptpSUpF/NSi/U3A+0V/EBjgdpXcCzpITPLC8v0nWfoUktnOytpZU+25/HGzRW+P93VTaT08kIFG8Cw2yIpQ/dAg2eTZwScUqePXpwKMD2vBIIQEEZpapi624g4004VGKQx/C8p4ZQg+/XydVM7doeG46IzM25nn5JQiqLkwZzft447fssR3bfSUxIWeiRmIy51FvYUU73yBF1BsPo0m7R3voZuwo7SEite6S2yW0ptjpAnP8=";

    private static JsonObject named(String item, String name, String... lore) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", name);
        com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
        for (String l : lore) {
            lines.add(l);
        }
        o.add("lore", lines);
        return o;
    }

    /** His equipped loadout 1, component for component: Hypixel leaves "Left-click to equip!" off the one he wears. */
    private static JsonObject hisLoadout1() {
        String star = "§6✪✪✪✪✪";
        return named("minecraft:player_head[profile={id:[I;-1531270147,1448752113,-1523863264,-881036133],"
                        + "properties:[{name:\"textures\",value:\"" + HIS_TEXTURE + "\",signature:\"" + HIS_SIGNATURE + "\"}]},"
                        + "custom_data={id:\"WITHER_GOGGLES\",modifier:\"necrotic\",rarity_upgrades:1,upgrade_level:5,"
                        + "hot_potato_count:15,donated_museum:1,uuid:\"207497c6-9fb3-49dd-b083-c7191193fb95\","
                        + "timestamp:1777333060054L,enchantments:{ultimate_wisdom:5,rejuvenate:5,growth:5,protection:5,"
                        + "respiration:3,aqua_affinity:1}},enchantments={aqua_affinity:1},"
                        + "tooltip_style=\"hypixel_skyblock:legendary\"]",
                "§aLoadout 1",
                "§7Helmet: §6Necrotic Wither Goggles " + star,
                "§7Chestplate: §dLoving Storm's Chestplate " + star,
                "§7Leggings: §dNecrotic Storm's Leggings " + star,
                "§7Boots: §5Rancher's Boots", "",
                "§7Necklace: §5Strengthened Bone Necklace " + star,
                "§7Cloak: §5Molten Cloak", "§7Belt: §5Strengthened Implosion Belt",
                "§7Gloves/Bracelet: §5Strengthened Gauntlet of Contagion", "",
                "§7Pet: §7[Lvl 100] §6Black Cat", "§7HOTM: §8None", "§7HOTF: §8None",
                "§7Power Stone: §aSighted", "§7Tuning Template Slot: §a1", "§7Favored Bait: §8None", "",
                "§eRight-click to edit");
    }

    /** A "(N/2) Loadouts" page as Hypixel lays it out: 12 heads, Close (barrier) at 49, Next Page at 44 on page 1 (opening
     *  {@code next}), Previous Page at 17 on page 2. Slot 0 stays empty: the picker's empty-cell reference. */
    private static JsonObject loadoutsPage(int page, JsonObject next) {
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "(" + page + "/2) Loadouts");
        spec.addProperty("rows", 6);
        JsonObject slots = new JsonObject();
        for (int i = 0; i < 12; i++) {
            int n = (page - 1) * 12 + i + 1;
            slots.add(String.valueOf(LOADOUT_SLOTS[i]), n == 1 ? hisLoadout1()
                    : named(i % 2 == 0 ? "minecraft:diamond_helmet" : "minecraft:golden_chestplate", "§aLoadout " + n,
                    "§7Helmet: §8None", "", "§eLeft-click to equip!", "§eRight-click to edit"));
        }
        slots.add("49", named("minecraft:barrier", "§cClose"));
        if (next != null) {
            slots.add("44", named("minecraft:arrow", "§aNext Page", "§ePage 2"));
            JsonObject on = new JsonObject();
            JsonObject open = new JsonObject();
            open.add("open", next);
            on.add("44", open);
            spec.add("on", on);
        } else {
            slots.add("17", named("minecraft:arrow", "§aPrevious Page", "§ePage 1"));
        }
        spec.add("slots", slots);
        return spec;
    }

    /** Share of pixels in the 9x9 lower right of the middle of the picker cell at gui point {@code at} that differ from the same pixels of the
     *  cell at {@code ref} (by more than 8 in a channel: his Wither Goggles head is dark, and two empty cells match exactly). */
    private static double differs(java.awt.image.BufferedImage img, double scale, int[] at, int[] ref) {
        for (int[] p : new int[][]{at, ref}) {
            if ((p[0] - 7) * scale < 0 || (p[1] - 7) * scale < 0 || (p[0] + 8) * scale >= img.getWidth()
                    || (p[1] + 8) * scale >= img.getHeight()) {
                throw new AssertionError("cell " + java.util.Arrays.toString(p) + " x" + scale + " is outside the "
                        + img.getWidth() + "x" + img.getHeight() + " screenshot");
            }
        }
        int n = 0;
        int diff = 0;
        for (int dy = -1; dy <= 7; dy++) { // below and right of the loadout number, above the equipped bar: the icon only
            for (int dx = -1; dx <= 7; dx++) {
                int a = img.getRGB((int) ((at[0] + dx) * scale), (int) ((at[1] + dy) * scale));
                int b = img.getRGB((int) ((ref[0] + dx) * scale), (int) ((ref[1] + dy) * scale));
                n++;
                if (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 8 || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 8
                        || Math.abs((a & 255) - (b & 255)) > 8) {
                    diff++;
                }
            }
        }
        return diff / (double) n;
    }

    private static void clickAt(Session c, int[] at) {
        c.ctx().runOnClient(mc -> ((Screen) McCompat.screen(mc)).mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false));
    }

    private static List<JsonObject> clicksSince(Session c, int before) {
        List<JsonObject> all = c.events("container.click");
        return all.subList(before, all.size());
    }

    /** loadout1-1105 (killer560, 2026-11-05: "for some reawson this isnt showing the first slot. i do have it eq uiped rigth
     *  now"; "also the next page button their doesnt work. and remove the close barrier block button"). A swap to his loadout 1
     *  through the real swapper on a fake /loadouts holding his exact head in slot 14: the cache keeps slot 14 (it used to
     *  save it empty, the clicked item being on the cursor), across a restart; the picker draws an icon there (pixels), marks
     *  it equipped, draws nothing at the Close barrier's slot and its click does nothing; a swap to loadout 13 pages with
     *  Next Page; the picker's Next Page shows page 2, Previous Page page 1, and a page-2 cell picks 13. */
    static void equipped(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1105-eq";
        Path file = c.onClient(mc -> (Path) Mod.field(CACHE, "FILE"));
        byte[] old = Files.exists(file) ? Files.readAllBytes(file) : null;
        Files.deleteIfExists(file);
        try {
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "forgetMemoryForTest"));
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", loadoutsPage(1, loadoutsPage(2, null)));

            // 1. The swap to loadout 1, as his 22:24:44 one: /loadouts, PICKUP on slot 14, menu closed.
            int before = c.events("container.click").size();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(SWAP, "requestLoadout", mc, 1)), "requestLoadout(1) returned false");
            c.waitUntil("the swap to loadout 1", mc -> !(Boolean) Mod.staticCall(SWAP, "isBusy"), 200);
            c.ctx().waitTicks(5);
            List<JsonObject> clicks = clicksSince(c, before);
            c.check(clicks.size() == 1 && clicks.get(0).get("slot").getAsInt() == 14
                    && c.onClient(mc -> (Boolean) Mod.staticCall(SWAP, "lastSwapped")), "the swap did not click slot 14 once: " + clicks);
            c.ctx().runOnClient(mc -> Mod.staticCall("shared.SharedWriter", "drain", 4000L));
            List<?> p1 = c.onClient(mc -> (List<?>) Mod.staticCall(CACHE, "get"));
            c.check(p1 != null && p1.size() >= 54, "the swap's /loadouts page was not cached: " + p1);
            ItemStack s14 = (ItemStack) p1.get(14);
            c.check(s14.is(Items.PLAYER_HEAD) && s14.getHoverName().getString().equals("Loadout 1"),
                    "his equipped loadout 1 (slot 14) was cached as " + s14 + ": the swap's click had it on the cursor");
            c.check(((ItemStack) p1.get(49)).is(Items.BARRIER), "the fake page's Close barrier is not in the cache: " + p1.get(49));
            // A restart reads the same back.
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "forgetMemoryForTest"));
            c.waitUntil("the cache read back", mc -> Mod.staticCall(CACHE, "get") != null, 100);
            c.check(c.onClient(mc -> ((ItemStack) ((List<?>) Mod.staticCall(CACHE, "get")).get(14)).is(Items.PLAYER_HEAD)),
                    "after a restart slot 14 is empty again");

            // 2. A swap to loadout 13 turns the page: Next Page (44) on page 1, then slot 14 on page 2.
            before = c.events("container.click").size();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(SWAP, "requestLoadout", mc, 13)), "requestLoadout(13) returned false");
            c.waitUntil("the swap to loadout 13", mc -> !(Boolean) Mod.staticCall(SWAP, "isBusy"), 300);
            c.ctx().waitTicks(5);
            clicks = clicksSince(c, before);
            c.check(clicks.size() == 2 && clicks.get(0).get("slot").getAsInt() == 44 && clicks.get(1).get("slot").getAsInt() == 14
                    && c.onClient(mc -> (Boolean) Mod.staticCall(SWAP, "lastSwapped")),
                    "loadout 13 should click Next Page (44) then slot 14: " + clicks);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(CACHE, "pages")) == 2, "the cache did not take the page count 2");
            List<?> p2 = c.onClient(mc -> (List<?>) Mod.staticCall(CACHE, "get", 2));
            c.check(p2 != null && ((ItemStack) p2.get(14)).getHoverName().getString().equals("Loadout 13"),
                    "page 2 was not cached with loadout 13 in slot 14: " + (p2 == null ? null : p2.get(14)));

            // 3. The picker: an icon in slot 14 (pixels), marked equipped, nothing at the Close slot.
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                Mod.call(store(), "save", layout(c, name, Map.of(0, "RUNEBOOK"), 0));
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "select", name));
            c.ctx().waitTicks(3);
            int[] at1 = openPickerCenter(c, 1);
            c.check(c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "equippedSlot")) == 14,
                    "the picker does not mark slot 14 (his worn loadout) equipped");
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(4);
            java.util.function.IntFunction<int[]> cell = s -> new int[]{at1[0] + (s % 9 - 5) * 24, at1[1] + (s / 9 - 1) * 24};
            Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-picker"));
            dev.testkit.harness.Report.screenshot(c.name(), shot);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            // The picker draws at the mod's own scale, so its coordinates are its own width, not the window's gui width.
            double scale = img.getWidth() / c.onClient(mc -> (double) McCompat.screen(mc).width);
            double d14 = differs(img, scale, cell.apply(14), cell.apply(0));
            double d15 = differs(img, scale, cell.apply(15), cell.apply(0));
            double d49 = differs(img, scale, cell.apply(49), cell.apply(0));
            c.check(d15 > 0.2, "control: loadout 2's icon should differ from the empty cell, " + d15);
            c.check(d14 > 0.2, "no icon drawn in slot 14 (his loadout 1): " + Math.round(d14 * 100) + "% of its pixels differ from an empty cell");
            c.check(d49 < 0.05, "the Close barrier is drawn at slot 49: " + Math.round(d49 * 100) + "% differ from an empty cell");
            clickAt(c, cell.apply(49));
            c.ctx().waitTicks(2);
            c.check(c.onClient(mc -> McCompat.screen(mc).getClass().getSimpleName().equals("InvSortLoadoutPick"))
                    && loadoutOf(c, name) == 0, "a click on the Close slot did something");

            // 4. Next Page / Previous Page in the picker.
            int[] next = c.onClient(mc -> (int[]) Mod.call(McCompat.screen(mc), "centerOf", -2));
            c.check(next[0] == cell.apply(44)[0] && next[1] == cell.apply(44)[1], "Next Page is not drawn at slot 44: "
                    + java.util.Arrays.toString(next));
            clickAt(c, next);
            c.check(c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "page")) == 2, "Next Page did not show page 2");
            int[] at13 = c.onClient(mc -> (int[]) Mod.call(McCompat.screen(mc), "centerOf", 13));
            c.check(at13[0] == at1[0] && at13[1] == at1[1], "page 2 is not drawn from the cache: 13 at " + java.util.Arrays.toString(at13));
            clickAt(c, c.onClient(mc -> (int[]) Mod.call(McCompat.screen(mc), "centerOf", -3)));
            c.check(c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "page")) == 1, "Previous Page did not go back to page 1");
            clickAt(c, next);
            clickAt(c, at13);
            c.waitUntil("back in the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.check(loadoutOf(c, name) == 13, "a click on page 2's first loadout should pick 13, got " + loadoutOf(c, name));
            openPickerCenter(c, 13);
            c.check(c.onClient(mc -> (Integer) Mod.call(McCompat.screen(mc), "page")) == 2, "the picker should open on loadout 13's page");
            c.ctx().runOnClient(mc -> McCompat.screen(mc).onClose());
            c.note("swap to 1: one PICKUP on 14, slot 14 cached as his head (also after a restart); swap to 13: clicks 44, 14, page 2 "
                    + "cached; picker: slot 14 " + Math.round(d14 * 100) + "% icon pixels (loadout 2 " + Math.round(d15 * 100)
                    + "%), equipped mark on 14, Close slot " + Math.round(d49 * 100) + "% and dead; Next -> page 2 from cache, "
                    + "Previous -> 1, page-2 cell picked 13, reopens on page 2");
        } finally {
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", null);
            c.ctx().runOnClient(mc -> Mod.call(store(), "delete", name));
            if (old == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, old);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CACHE, "forgetMemoryForTest"));
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    /** Opens the picker and answers where choice n is drawn. Leaves the picker open. */
    private static int[] openPickerCenter(Session c, int n) {
        c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "openLoadoutPicker"));
        c.waitUntil("the loadout picker", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InvSortLoadoutPick"), 40);
        c.ctx().waitTicks(3);
        return c.onClient(mc -> (int[]) Mod.call(McCompat.screen(mc), "centerOf", n));
    }
}
