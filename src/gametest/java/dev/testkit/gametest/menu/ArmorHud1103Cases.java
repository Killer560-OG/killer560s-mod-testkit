package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Armor HUD and Wrong Armor Alert (mod armorhud-1103; killer560, 2026-11-03: "make an armor hud thing that shows my helmet
 * cp legs and boots with an option for equipment as well" and "a mining alert for if you are mining in a non mining armor
 * set"). Both draw only: every case is on the session's GrimAC server, and the digging is done in adventure mode, where
 * holding attack on a block sends nothing.
 * <ul>
 *   <li>1103-menu-armorhud-armor: four worn pieces draw four icons (the in-game draw count, and the element's HudSeen stamp);
 *       taking the helmet off draws three.</li>
 *   <li>1103-menu-armorhud-equipment: with the cache cleared only the armor draws; Hypixel's "Your Equipment and Stats"
 *       menu with a necklace, cloak and belt in slots 10/19/28 and the empty-slot pane in 37 is read into the cache, the
 *       cache file reads back the same after a reload, and the HUD draws 4 + 3 icons; Show Equipment off draws 4.</li>
 *   <li>1103-menu-armorhud-settings: every setting survives save + load; the box is 40x76 vertical, 76x40 horizontal and
 *       22x76 vertical without equipment.</li>
 *   <li>1103-menu-wrongarmor: on the Dwarven Mines with a pickaxe, digging in non-mining armor pops "Wrong Armor!" (drawn);
 *       a full Goblin set, two mining sets mixed, not digging, off the island and the switch off are each silent.</li>
 * </ul>
 */
final class ArmorHud1103Cases {

    private static final String CFG = "armorhud.ArmorHudConfig";
    private static final String FEAT = "armorhud.ArmorHudFeature";
    private static final String MQ = "miningqol.MiningQolConfig";
    private static final String WA = "miningqol.WrongArmorAlert";
    private static final String[] SLOTS = {"armor.head", "armor.chest", "armor.legs", "armor.feet"};
    private static final String[] VANILLA = {"iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"};

    private ArmorHud1103Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1103-menu-armorhud-armor", ArmorHud1103Cases::armor);
        MenuSuite.test(s, "1103-menu-armorhud-equipment", ArmorHud1103Cases::equipment);
        MenuSuite.test(s, "1103-menu-armorhud-settings", ArmorHud1103Cases::settings);
        MenuSuite.test(s, "1103-menu-armorhud-loadouts", ArmorHud1103Cases::loadouts);
        MenuSuite.test(s, "1103-menu-wrongarmor", ArmorHud1103Cases::wrongArmor);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------------

    private static String stack(String item, String id) {
        return "minecraft:" + item + "[custom_data={id:\"" + id + "\"}]";
    }

    /** A Skyblock item whose lore ends with Hypixel's rarity line ("LEGENDARY NECKLACE"). */
    private static String lored(String item, String id, String rarityLine) {
        return "minecraft:" + item + "[custom_data={id:\"" + id + "\"},lore=[{text:\"\",italic:false},{text:\"" + rarityLine
                + "\",italic:false,bold:true}]]";
    }

    /** Puts {@code ids} (helmet..boots; null = nothing) on him and waits until the client sees them. */
    private static void wear(Session c, String... ids) {
        for (int i = 0; i < 4; i++) {
            c.server().command("item replace entity @a " + SLOTS[i] + " with "
                    + (ids[i] == null ? "minecraft:air" : stack(VANILLA[i], ids[i])));
        }
        List<String> want = Arrays.asList(ids);
        c.waitUntil("the client wears " + want, mc -> want.equals(worn(mc)), 60);
        c.ctx().waitTicks(2);
    }

    private static List<String> worn(net.minecraft.client.Minecraft mc) {
        List<String> out = new ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot s : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            out.add((String) Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getItemBySlot(s)));
        }
        return out;
    }

    private static int icons(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEAT, "iconsLastFrame"));
    }

    private static int frames(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEAT, "framesDrawn"));
    }

    /** Waits for frames drawn after now that drew exactly {@code n} icons. */
    private static void awaitIcons(Session c, int n, String what) {
        int f0 = frames(c);
        c.waitUntil(what + ": " + n + " icons drawn", mc -> (Integer) Mod.staticCall(FEAT, "framesDrawn") > f0 + 2
                && (Integer) Mod.staticCall(FEAT, "iconsLastFrame") == n, 60);
    }

    private static void defaults(Session c) {
        c.ctx().runOnClient(mc -> {
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setShowEquipment", true);
            Mod.set(CFG, "setLayout", Mod.enumValue(CFG + "$Layout", "VERTICAL"));
            Mod.set(CFG, "setShowDurability", false);
            Mod.set(CFG, "setBackground", true);
            Mod.call(Mod.cfg(CFG), "save");
        });
    }

    // ---- 1103-menu-armorhud-armor -----------------------------------------------------------------------------------

    static void armor(Session c) {
        MenuKit.reset(c);
        try {
            defaults(c);
            c.ctx().runOnClient(mc -> {
                Mod.set(CFG, "setShowEquipment", false);
                Mod.staticCall(FEAT, "clearCacheForTest");
            });
            wear(c, "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            awaitIcons(c, 4, "four worn pieces");
            c.waitUntil("the Armor HUD stamped as drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "armor_hud"), 40);
            wear(c, null, "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            awaitIcons(c, 3, "helmet off");
            c.note("4 icons with four pieces, 3 with the helmet off; HudSeen stamped");
        } finally {
            wear(c, null, null, null, null);
            defaults(c);
        }
    }

    // ---- 1103-menu-armorhud-equipment -------------------------------------------------------------------------------

    private static JsonObject equipmentMenu() {
        JsonObject spec = MenuKit.obj("{\"title\":\"Your Equipment and Stats\",\"rows\":6,\"fill\":true,\"slots\":{},\"on\":{}}");
        JsonObject slots = spec.getAsJsonObject("slots");
        slots.addProperty("10", stack("player_head", "LAVA_SHELL_NECKLACE"));
        slots.addProperty("19", stack("leather_chestplate", "GHAST_CLOAK"));
        slots.addProperty("28", stack("player_head", "IMPLOSION_BELT"));
        slots.addProperty("37", "minecraft:light_gray_stained_glass_pane[custom_name={text:\"Empty Equipment Slot\","
                + "italic:false}]");
        return spec;
    }

    static void equipment(Session c) {
        MenuKit.reset(c);
        try {
            defaults(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(FEAT, "clearCacheForTest"));
            wear(c, "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            awaitIcons(c, 4, "no equipment seen yet");
            int caps = c.onClient(mc -> (Integer) Mod.staticCall(FEAT, "captures"));
            MenuKit.show(c, equipmentMenu());
            MenuKit.awaitScreen(c, "Your Equipment and Stats", 100);
            List<String> want = Arrays.asList("LAVA_SHELL_NECKLACE", "GHAST_CLOAK", "IMPLOSION_BELT", "");
            c.waitUntil("the equipment read from the menu", mc -> want.equals(Mod.staticCall(FEAT, "equipmentIds")), 40);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(FEAT, "captures")) > caps, "no capture counted");
            MenuKit.reset(c);
            MenuKit.awaitNoScreen(c, 40);
            awaitIcons(c, 7, "armor + three cached pieces");
            // The cache file: forget the memory, read the file again.
            c.ctx().runOnClient(mc -> Mod.staticCall(FEAT, "reloadCacheForTest"));
            List<?> reread = c.onClient(mc -> new ArrayList<>((List<?>) Mod.staticCall(FEAT, "equipmentIds")));
            c.check(want.equals(reread), "the cache file read back " + reread);
            awaitIcons(c, 7, "after the reload");
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setShowEquipment", false));
            awaitIcons(c, 4, "Show Equipment off");
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setShowEquipment", true));
            // Owner 2026-11-03: /stats too, and a layout unlike the wiki's: pieces found by their rarity line anywhere.
            JsonObject stats = MenuKit.obj("{\"title\":\"Your Stats\",\"rows\":4,\"fill\":true,\"slots\":{},\"on\":{}}");
            JsonObject ss = stats.getAsJsonObject("slots");
            ss.addProperty("3", lored("player_head", "MAGMA_NECKLACE", "LEGENDARY NECKLACE"));
            ss.addProperty("21", lored("player_head", "MOLTEN_BRACELET", "EPIC BRACELET"));
            ss.addProperty("22", lored("player_head", "MOLTEN_BELT", "EPIC BELT"));
            ss.addProperty("30", lored("leather_chestplate", "NOT_EQUIPMENT", "LEGENDARY CHESTPLATE"));
            MenuKit.show(c, stats);
            MenuKit.awaitScreen(c, "Your Stats", 100);
            List<String> want2 = Arrays.asList("MAGMA_NECKLACE", "GHAST_CLOAK", "MOLTEN_BELT", "MOLTEN_BRACELET");
            c.waitUntil("equipment read from /stats by kind", mc -> want2.equals(Mod.staticCall(FEAT, "equipmentIds")), 40);
            MenuKit.reset(c);
            MenuKit.awaitNoScreen(c, 40);
            awaitIcons(c, 8, "armor + four pieces from /stats");
            c.note("menu read " + want + "; file read back the same; 7 icons, 4 with Show Equipment off; /stats by kind: "
                    + want2 + ", 8 icons");
        } finally {
            MenuKit.reset(c);
            wear(c, null, null, null, null);
            c.ctx().runOnClient(mc -> Mod.staticCall(FEAT, "clearCacheForTest"));
            defaults(c);
        }
    }

    // ---- 1103-menu-armorhud-loadouts --------------------------------------------------------------------------------

    private static JsonObject statsMenu(String necklace, String cloak, String belt, String bracelet) {
        JsonObject spec = MenuKit.obj("{\"title\":\"Your Stats\",\"rows\":4,\"fill\":true,\"slots\":{},\"on\":{}}");
        JsonObject ss = spec.getAsJsonObject("slots");
        ss.addProperty("3", lored("player_head", necklace, "LEGENDARY NECKLACE"));
        ss.addProperty("12", lored("leather_chestplate", cloak, "LEGENDARY CLOAK"));
        ss.addProperty("21", lored("player_head", belt, "EPIC BELT"));
        ss.addProperty("22", lored("player_head", bracelet, "EPIC BRACELET"));
        return spec;
    }

    private static void openStats(Session c, JsonObject spec, String why) {
        MenuKit.show(c, spec);
        MenuKit.awaitScreen(c, "Your Stats", 100);
        c.ctx().waitTicks(6);
        MenuKit.reset(c);
        MenuKit.awaitNoScreen(c, 40);
    }

    private static int swaps(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEAT, "loadoutSwapsForTest"));
    }

    static void loadouts(Session c) throws Exception {
        MenuKit.reset(c);
        int was = c.onClient(mc -> ((Number) Mod.staticCall("loadoutkeybinds.LoadoutHighlight", "lastEquippedLoadout")).intValue());
        try {
            defaults(c);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(FEAT, "clearCacheForTest");
                Mod.staticCall("loadoutkeybinds.LoadoutHighlight", "setLastEquippedForTest", 0);
            });
            wear(c, "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            List<String> a = Arrays.asList("SET_A_NECKLACE", "SET_A_CLOAK", "SET_A_BELT", "SET_A_BRACELET");
            List<String> b = Arrays.asList("SET_B_NECKLACE", "SET_B_CLOAK", "SET_B_BELT", "SET_B_BRACELET");
            c.hx().chat("§aYou equipped Loadout 1!");
            c.ctx().waitTicks(4);
            openStats(c, statsMenu("SET_A_NECKLACE", "SET_A_CLOAK", "SET_A_BELT", "SET_A_BRACELET"), "set A");
            c.waitUntil("set A read", mc -> a.equals(Mod.staticCall(FEAT, "equipmentIds")), 40);
            int s0 = swaps(c);
            c.hx().chat("§aYou equipped Loadout 2!");
            c.ctx().waitTicks(6);
            openStats(c, statsMenu("SET_B_NECKLACE", "SET_B_CLOAK", "SET_B_BELT", "SET_B_BRACELET"), "set B");
            c.waitUntil("set B read", mc -> b.equals(Mod.staticCall(FEAT, "equipmentIds")), 40);
            c.hx().chat("§aYou equipped Loadout 1!");
            c.waitUntil("Loadout 1's set A is back with no menu", mc -> a.equals(Mod.staticCall(FEAT, "equipmentIds")), 60);
            c.check(swaps(c) > s0, "no swap counted (" + s0 + " -> " + swaps(c) + ")");
            awaitIcons(c, 8, "armor + set A");
            c.hx().chat("§aYou equipped Loadout 2!");
            c.waitUntil("Loadout 2's set B is back", mc -> b.equals(Mod.staticCall(FEAT, "equipmentIds")), 60);
            // The file keeps both loadouts: forget memory, read the file, swap again.
            c.note("cache file before the reload: " + c.onClient(mc -> {
                try {
                    return java.nio.file.Files.readString((java.nio.file.Path) Mod.field(FEAT, "CACHE_PATH"));
                } catch (Exception e) {
                    return e.toString();
                }
            }));
            c.ctx().runOnClient(mc -> Mod.staticCall(FEAT, "reloadCacheForTest"));
            c.hx().chat("§aYou equipped Loadout 1!");
            c.waitUntil("set A from the reloaded file", mc -> a.equals(Mod.staticCall(FEAT, "equipmentIds")), 60);
            c.hx().chat("§aYou equipped Loadout 2!");
            c.waitUntil("set B from the reloaded file", mc -> b.equals(Mod.staticCall(FEAT, "equipmentIds")), 60);
            c.note("Loadout 1 -> A, 2 -> B, back to 1 shows A with no menu; swaps " + swaps(c) + "; file reload keeps both");
        } finally {
            MenuKit.reset(c);
            wear(c, null, null, null, null);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(FEAT, "clearCacheForTest");
                Mod.staticCall("loadoutkeybinds.LoadoutHighlight", "setLastEquippedForTest", was);
            });
            defaults(c);
        }
    }

    // ---- 1103-menu-armorhud-settings --------------------------------------------------------------------------------

    static void settings(Session c) {
        try {
            defaults(c);
            int[] v = c.onClient(mc -> size());
            c.check(v[0] == 40 && v[1] == 76, "vertical with equipment " + v[0] + "x" + v[1]);
            c.ctx().runOnClient(mc -> {
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setShowEquipment", false);
                Mod.set(CFG, "setLayout", Mod.enumValue(CFG + "$Layout", "HORIZONTAL"));
                Mod.set(CFG, "setShowDurability", true);
                Mod.set(CFG, "setBackground", false);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
            });
            String got = c.onClient(mc -> Mod.get(CFG, "isEnabledRaw") + " " + Mod.get(CFG, "isShowEquipment") + " "
                    + Mod.get(CFG, "getLayout") + " " + Mod.get(CFG, "isShowDurability") + " " + Mod.get(CFG, "isBackground"));
            c.check("false false HORIZONTAL true false".equals(got), "after save + load: " + got);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setShowEquipment", true));
            int[] h = c.onClient(mc -> size());
            c.check(h[0] == 76 && h[1] == 40, "horizontal with equipment " + h[0] + "x" + h[1]);
            c.ctx().runOnClient(mc -> {
                Mod.set(CFG, "setLayout", Mod.enumValue(CFG + "$Layout", "VERTICAL"));
                Mod.set(CFG, "setShowEquipment", false);
            });
            int[] n = c.onClient(mc -> size());
            c.check(n[0] == 22 && n[1] == 76, "vertical without equipment " + n[0] + "x" + n[1]);
            // Wrong Armor Alert's two settings.
            c.ctx().runOnClient(mc -> {
                Mod.set(MQ, "setWrongArmor", false);
                Mod.set(MQ, "setWrongArmorSound", true);
                Mod.call(Mod.cfg(MQ), "save");
                Mod.staticCall(MQ, "load");
            });
            String wa = c.onClient(mc -> Mod.get(MQ, "isWrongArmorRaw") + " " + Mod.get(MQ, "isWrongArmorSound"));
            c.check("false true".equals(wa), "Wrong Armor after save + load: " + wa);
            c.note("Armor HUD: " + got + "; sizes 40x76 / 76x40 / 22x76; Wrong Armor: " + wa);
        } finally {
            defaults(c);
            c.ctx().runOnClient(mc -> {
                Mod.set(MQ, "setWrongArmor", true);
                Mod.set(MQ, "setWrongArmorSound", false);
                Mod.call(Mod.cfg(MQ), "save");
            });
        }
    }

    private static int[] size() {
        Object e = Mod.field(FEAT + "$Element", "INSTANCE");
        return new int[]{(Integer) Mod.call(e, "width"), (Integer) Mod.call(e, "height")};
    }

    // ---- 1103-menu-wrongarmor ---------------------------------------------------------------------------------------

    private static void mines(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
        c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
        c.waitUntil("on the Dwarven Mines", mc -> (Boolean) Mod.staticCall("miningqol.MiningState", "onMiningIsland"), 100);
    }

    private static void dig(Session c, boolean on) {
        c.ctx().runOnClient(mc -> {
            if (on) {
                mc.player.setXRot(90f);
            }
            mc.options.keyAttack.setDown(on);
        });
    }

    private static String reason(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(WA, "lastReason"));
    }

    private static boolean showing(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(WA, "showing"));
    }

    /** Waits {@code ticks} and checks the alert stayed down the whole time and the reason starts with {@code why}. */
    private static void silent(Session c, String what, String why, int ticks) {
        int shown = c.onClient(mc -> (Integer) Mod.staticCall(WA, "shownCount"));
        for (int i = 0; i < ticks; i += 5) {
            c.ctx().waitTicks(5);
            c.check(!showing(c), what + ": the alert showed (" + reason(c) + ")");
        }
        c.check(c.onClient(mc -> (Integer) Mod.staticCall(WA, "shownCount")) == shown, what + ": the alert popped");
        String r = reason(c);
        c.check(r.startsWith(why), what + ": reason '" + r + "', want '" + why + "'");
    }

    static void wrongArmor(Session c) {
        MenuKit.reset(c);
        String mode = c.onClient(mc -> mc.gameMode.getPlayerMode().name().toLowerCase(Locale.ROOT));
        List<String> notes = new ArrayList<>();
        try {
            c.ctx().runOnClient(mc -> {
                Mod.set(MQ, "setWrongArmor", true);
                Mod.set(MQ, "setWrongArmorSound", true);
            });
            // Adventure: holding attack on the platform sends nothing and breaks nothing.
            c.server().command("gamemode adventure @p");
            c.server().command("item replace entity @a weapon.mainhand with " + stack("diamond_pickaxe", "MITHRIL_PICKAXE"));
            c.waitUntil("the pickaxe in hand", mc -> "MITHRIL_PICKAXE".equals(Mod.staticCall("cheatutils.CheatUtils",
                    "skyblockId", mc.player.getMainHandItem())), 60);
            mines(c);
            // 1. Non-mining armor, digging: the alert.
            wear(c, "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            int shown = c.onClient(mc -> (Integer) Mod.staticCall(WA, "shownCount"));
            int frames = c.onClient(mc -> (Integer) Mod.staticCall(WA, "frames"));
            dig(c, true);
            c.waitUntil("aimed at a block", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h
                    && h.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, 40);
            c.waitUntil("Wrong Armor! showing", mc -> (Boolean) Mod.staticCall(WA, "showing"), 40);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(WA, "shownCount")) == shown + 1, "shown count did not rise by one");
            c.waitUntil("Wrong Armor! drawn", mc -> (Integer) Mod.staticCall(WA, "frames") > frames + 2
                    && (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "mining_wrong_armor"), 40);
            notes.add("non-mining armor: " + reason(c));
            // The alarm keeps going while the armor stays wrong: about one beep a second, none once it is right.
            int beeps0 = c.onClient(mc -> (Integer) Mod.staticCall(WA, "soundCount"));
            c.ctx().waitTicks(65);
            int beeps = c.onClient(mc -> (Integer) Mod.staticCall(WA, "soundCount")) - beeps0;
            c.check(beeps >= 2 && beeps <= 5, "the wrong-armor alarm beeped " + beeps + " times in 3.25 s, wanted about 3");
            notes.add("alarm " + beeps + " beeps in 3.25 s");
            // An empty slot is wrong too.
            wear(c, "GOBLIN_HELMET", "GOBLIN_CHESTPLATE", "GOBLIN_LEGGINGS", null);
            c.ctx().waitTicks(10);
            c.check(showing(c) && reason(c).startsWith("wrong: FEET"), "empty boots: " + reason(c));
            // 2. A full Goblin set: gone within a moment, and silent.
            wear(c, "GOBLIN_HELMET", "GOBLIN_CHESTPLATE", "GOBLIN_LEGGINGS", "GOBLIN_BOOTS");
            c.waitUntil("the alert gone in a mining set", mc -> !(Boolean) Mod.staticCall(WA, "showing"), 30);
            int beepsDone = c.onClient(mc -> (Integer) Mod.staticCall(WA, "soundCount"));
            c.ctx().waitTicks(45);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(WA, "soundCount")) == beepsDone, "the alarm kept beeping in a mining set");
            silent(c, "full Goblin set", "mining set", 30);
            // Two mining sets mixed (lowercase ids as the wiki writes Glacite's).
            wear(c, "DIVAN_HELMET", "glacite_chestplate", "GOBLIN_LEGGINGS", "SORROW_BOOTS");
            silent(c, "mixed mining sets", "mining set", 20);
            // 3. Non-mining armor, not digging (the button let go more than 3 s ago).
            wear(c, "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS");
            c.waitUntil("Wrong Armor! back while digging", mc -> (Boolean) Mod.staticCall(WA, "showing"), 40);
            dig(c, false);
            c.waitUntil("the alert gone once he stops digging", mc -> !(Boolean) Mod.staticCall(WA, "showing"), 100);
            silent(c, "not digging", "not digging", 20);
            // 4. Digging off the island.
            HxKit.hub(c);
            c.waitUntil("off the mining islands", mc -> !(Boolean) Mod.staticCall("miningqol.MiningState", "onMiningIsland"), 100);
            dig(c, true);
            silent(c, "the Hub", "not on a mining island", 30);
            // 5. The switch off, on the island, digging.
            mines(c);
            c.waitUntil("Wrong Armor! on the island again", mc -> (Boolean) Mod.staticCall(WA, "showing"), 40);
            c.ctx().runOnClient(mc -> Mod.set(MQ, "setWrongArmor", false));
            c.waitUntil("the alert gone with the switch off", mc -> !(Boolean) Mod.staticCall(WA, "showing"), 20);
            silent(c, "switch off", "off", 20);
            // No mining tool: the sword in hand first, then the switch back on (still digging, still the wrong armor).
            c.server().command("item replace entity @a weapon.mainhand with " + stack("diamond_sword", "HYPERION"));
            c.waitUntil("the sword in hand", mc -> "HYPERION".equals(Mod.staticCall("cheatutils.CheatUtils",
                    "skyblockId", mc.player.getMainHandItem())), 60);
            c.ctx().runOnClient(mc -> Mod.set(MQ, "setWrongArmor", true));
            silent(c, "holding a sword", "no mining tool", 20);
            notes.add("silent in a Goblin set, mixed sets, not digging, the Hub, switch off, a sword");
            c.note(String.join("; ", notes));
        } finally {
            dig(c, false);
            c.ctx().runOnClient(mc -> {
                Mod.set(MQ, "setWrongArmor", true);
                Mod.set(MQ, "setWrongArmorSound", false);
            });
            c.server().command("item replace entity @a weapon.mainhand with minecraft:air");
            wear(c, null, null, null, null);
            c.server().command("gamemode " + mode + " @p");
            HxKit.hub(c);
        }
    }
}
